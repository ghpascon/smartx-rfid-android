#!/usr/bin/env bash
# Funções compartilhadas pelos scripts da biblioteca (use: source "$(dirname "$0")/_common.sh").

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_JSON="$ROOT_DIR/config.json"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "Erro: '$1' não encontrado. Instale-o e tente de novo."; exit 1; }
}

require_cmd jq
[[ -f "$CONFIG_JSON" ]] || { echo "Erro: não encontrou $CONFIG_JSON"; exit 1; }

cfg() { jq -r "$1" "$CONFIG_JSON"; }

LIB_VERSION="$(cfg '.library.version')"
LIB_GROUP="$(cfg '.library.groupId')"
LIB_ARTIFACT="$(cfg '.library.artifactId')"

gradle() { (cd "$ROOT_DIR" && ./gradlew "$@"); }

# Exige credenciais do GitHub Packages (GITHUB_ACTOR/GITHUB_TOKEN ou ~/.gradle/gradle.properties gpr.user/gpr.key)
require_gpr_credentials() {
  if [[ -n "${GITHUB_TOKEN:-}" && -n "${GITHUB_ACTOR:-}" ]]; then return 0; fi
  if grep -qs '^gpr.key=' "$HOME/.gradle/gradle.properties" && grep -qs '^gpr.user=' "$HOME/.gradle/gradle.properties"; then return 0; fi
  cat <<'EOF'
Erro: credenciais do GitHub Packages não encontradas.
Defina GITHUB_ACTOR e GITHUB_TOKEN (PAT com write:packages) ou adicione em ~/.gradle/gradle.properties:
  gpr.user=SEU_USUARIO
  gpr.key=SEU_TOKEN
EOF
  exit 1
}
