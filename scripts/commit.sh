#!/usr/bin/env bash
# Sobe a versão da lib (config.json), registra no CHANGELOG, commita, cria a tag vX.Y.Z e dá push.
# A tag dispara .github/workflows/publish.yml, que publica a lib no GitHub Packages.
set -euo pipefail
source "$(dirname "$0")/_common.sh"

# Branch que dispara o workflow CI (.github/workflows/ci.yml: push em main).
CI_BRANCH="main"
REMOTE="origin"

# ---------------------------------------------------------------------------
# Pré-requisitos para rodar os dois workflows (CI e Publish)
# ---------------------------------------------------------------------------
preflight() {
  local ok=1
  fail() { echo "  [X] $1"; ok=0; }
  pass() { echo "  [ok] $1"; }

  echo "Verificando pre-requisitos dos workflows (CI + Publish)..."

  # Workflows existem e estao versionados (ou serao commitados pelo add -A)
  for wf in ci publish; do
    if [[ -f "$ROOT_DIR/.github/workflows/$wf.yml" ]]; then pass "workflow $wf.yml presente"
    else fail "workflow .github/workflows/$wf.yml nao encontrado"; fi
  done

  # CI so dispara em push na branch main
  local branch
  branch="$(git -C "$ROOT_DIR" rev-parse --abbrev-ref HEAD)"
  if [[ "$branch" == "$CI_BRANCH" ]]; then pass "branch atual: $branch (dispara o CI)"
  else fail "branch atual '$branch': o CI so roda em push na '$CI_BRANCH'"; fi

  # Remote configurado e sem commits novos que impeçam o push
  if git -C "$ROOT_DIR" remote get-url "$REMOTE" >/dev/null 2>&1; then
    pass "remote '$REMOTE' configurado"
    if git -C "$ROOT_DIR" fetch --quiet --tags "$REMOTE" 2>/dev/null; then
      if git -C "$ROOT_DIR" rev-parse --verify --quiet "$REMOTE/$CI_BRANCH" >/dev/null; then
        local behind
        behind="$(git -C "$ROOT_DIR" rev-list --count "HEAD..$REMOTE/$CI_BRANCH")"
        if [[ "$behind" -eq 0 ]]; then pass "branch local nao esta atras do remote"
        else fail "branch local esta $behind commit(s) atras de $REMOTE/$CI_BRANCH (faca pull/rebase)"; fi
      fi
    else
      fail "nao foi possivel acessar o remote '$REMOTE' (fetch falhou)"
    fi
  else
    fail "remote '$REMOTE' nao configurado"
  fi

  # Segredo GHTOKEN usado pelo workflow Publish (secrets.GHTOKEN)
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    if gh secret list -R "$(cfg '.repository.owner')/$(cfg '.repository.name')" 2>/dev/null | grep -q '^GHTOKEN\b'; then
      pass "secret GHTOKEN configurado no GitHub"
    else
      fail "secret GHTOKEN nao encontrado (Settings > Secrets and variables > Actions; PAT com write:packages)"
    fi
  else
    echo "  [!] gh CLI ausente/nao autenticado: nao deu para validar o secret GHTOKEN"
  fi

  # Todos os SDKs de fabricantes precisam existir em vendor-sdks/repo (o CI resolve por la)
  local sdk_group_path="${LIB_GROUP_SDK//./\/}" art ver typ missing=0
  while IFS=$'\t' read -r art ver typ; do
    [[ -f "$ROOT_DIR/vendor-sdks/repo/$sdk_group_path/$art/$ver/$art-$ver.$typ" ]] || { fail "SDK ausente em vendor-sdks/repo: $art-$ver.$typ (rode scripts/publish_sdks.sh)"; missing=1; }
  done < <(jq -r '.vendorSdks.artifacts[] | [.artifactId, .version, .type] | @tsv' "$CONFIG_JSON")
  [[ $missing -eq 0 ]] && pass "SDKs de fabricantes presentes em vendor-sdks/repo"

  # Mesmos comandos do CI/Publish, para nao quebrar depois do push
  if [[ "${SKIP_TESTS:-0}" == "1" ]]; then
    echo "  [!] SKIP_TESTS=1: testes/AAR locais ignorados"
  else
    echo "  ... rodando testes e assembleRelease localmente (SKIP_TESTS=1 para pular)"
    if gradle :smartx-rfid:testDebugUnitTest :smartx-rfid:assembleRelease -q; then pass "testes e AAR OK"
    else fail "testes/AAR falharam (o CI e o Publish quebrariam)"; fi
  fi

  if [[ $ok -eq 0 ]]; then
    echo "Pre-requisitos nao atendidos. Nada foi alterado."
    exit 1
  fi
}

LIB_GROUP_SDK="$(cfg '.vendorSdks.groupId')"
preflight

echo "Tipo de versao (atual: $LIB_VERSION):"
echo "  1) patch"
echo "  2) minor"
echo "  3) major"
read -r -p "Escolha (major/minor/patch): " VERSION_TYPE

case "${VERSION_TYPE,,}" in
  1) VERSION_TYPE="patch" ;;
  2) VERSION_TYPE="minor" ;;
  3) VERSION_TYPE="major" ;;
  major|minor|patch) VERSION_TYPE="${VERSION_TYPE,,}" ;;
  *)
    echo "Opcao invalida. Use major, minor ou patch."
    exit 1
    ;;
esac

read -r -p "Nome do commit: " COMMIT_MESSAGE
if [[ -z "${COMMIT_MESSAGE// }" ]]; then
  echo "Erro: o nome do commit nao pode ser vazio."
  exit 1
fi

IFS='.' read -r MAJOR MINOR PATCH <<< "$LIB_VERSION"
case "$VERSION_TYPE" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
esac
NEW_VERSION="$MAJOR.$MINOR.$PATCH"

# A tag nao pode existir (local ou remota), senao o Publish nao dispara/duplica
if git -C "$ROOT_DIR" rev-parse -q --verify "refs/tags/v$NEW_VERSION" >/dev/null \
   || git -C "$ROOT_DIR" ls-remote --exit-code --tags "$REMOTE" "refs/tags/v$NEW_VERSION" >/dev/null 2>&1; then
  echo "Erro: a tag v$NEW_VERSION ja existe."
  exit 1
fi

# Atualiza config.json
TMP="$(mktemp)"
jq --arg v "$NEW_VERSION" '.library.version = $v' "$CONFIG_JSON" > "$TMP" && mv "$TMP" "$CONFIG_JSON"

# Registra no CHANGELOG (abaixo do cabeçalho)
CHANGELOG="$ROOT_DIR/CHANGELOG.md"
if [[ -f "$CHANGELOG" ]]; then
  ENTRY="## [$NEW_VERSION] - $(date +%Y-%m-%d)\n\n- $COMMIT_MESSAGE\n"
  awk -v entry="$ENTRY" 'BEGIN{done=0} /^## / && !done {printf "%b\n", entry; done=1} {print}' "$CHANGELOG" > "$CHANGELOG.tmp" && mv "$CHANGELOG.tmp" "$CHANGELOG"
fi

echo "Versao atual: $LIB_VERSION"
echo "Nova versao:  $NEW_VERSION"

git -C "$ROOT_DIR" add -A

if git -C "$ROOT_DIR" diff --cached --quiet; then
  echo "Nenhuma alteracao para commitar."
  exit 0
fi

git -C "$ROOT_DIR" commit -m "$COMMIT_MESSAGE"
git -C "$ROOT_DIR" tag -a "v$NEW_VERSION" -m "v$NEW_VERSION"

echo "Commit e tag v$NEW_VERSION criados com sucesso."

# Um unico push com branch + tag: dispara o CI (push em main) e o Publish (tag v*)
git -C "$ROOT_DIR" push --atomic "$REMOTE" "$CI_BRANCH" "v$NEW_VERSION"

echo "Workflows disparados. Acompanhe em: $(cfg '.repository.url')/actions"
