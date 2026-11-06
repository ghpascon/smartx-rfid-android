#!/usr/bin/env bash
# Sobe a versão da lib (config.json), registra no CHANGELOG, commita, cria a tag vX.Y.Z e dá push.
# A tag dispara .github/workflows/publish.yml, que publica a lib no GitHub Packages.
set -euo pipefail
source "$(dirname "$0")/_common.sh"

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

git -C "$ROOT_DIR" push
git -C "$ROOT_DIR" push origin "v$NEW_VERSION"
