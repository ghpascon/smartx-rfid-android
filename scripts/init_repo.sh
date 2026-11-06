#!/usr/bin/env bash
# Inicializa o repositório git da lib e aponta o remote para o GitHub (config.json -> repository.url).
# Idempotente: pode ser rodado de novo depois de mover a pasta para outro lugar.
set -euo pipefail
source "$(dirname "$0")/_common.sh"

REPO_URL="$(cfg '.repository.url')"

cd "$ROOT_DIR"

if [[ ! -d .git ]]; then
  git init -b main
  echo "✓ Repositório git criado"
fi

if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REPO_URL"
else
  git remote add origin "$REPO_URL"
fi
echo "✓ origin -> $REPO_URL"

if ! git rev-parse HEAD >/dev/null 2>&1; then
  git add -A
  git commit -m "Initial commit: smartx-rfid $LIB_VERSION"
  git tag -a "v$LIB_VERSION" -m "v$LIB_VERSION"
  echo "✓ Commit inicial e tag v$LIB_VERSION criados"
  echo "Para subir: git push -u origin main && git push origin v$LIB_VERSION"
fi
