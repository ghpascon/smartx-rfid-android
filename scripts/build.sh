#!/usr/bin/env bash
# Compila a biblioteca (AAR release) e roda os testes unitários.
#   ./scripts/build.sh            -> testes + AAR
#   ./scripts/build.sh --no-tests -> apenas AAR
set -euo pipefail
source "$(dirname "$0")/_common.sh"

echo "▶ Build $LIB_GROUP:$LIB_ARTIFACT:$LIB_VERSION"

if [[ "${1:-}" == "--no-tests" ]]; then
  gradle :smartx-rfid:assembleRelease --quiet
else
  gradle :smartx-rfid:testDebugUnitTest :smartx-rfid:assembleRelease --quiet
fi

AAR="$ROOT_DIR/smartx-rfid/build/outputs/aar/smartx-rfid-release.aar"
if [[ -f "$AAR" ]]; then
  echo "✓ Build concluído:"
  echo "$AAR"
else
  echo "✗ AAR não encontrado"
  exit 1
fi
