#!/usr/bin/env bash
# Publica a versão atual da lib (config.json) no GitHub Packages.
# Normalmente quem faz isso é o workflow .github/workflows/publish.yml ao criar a tag vX.Y.Z
# (scripts/commit.sh). Use este script para publicar manualmente.
#   ./scripts/publish.sh            -> só a lib
#   ./scripts/publish.sh --with-sdks -> SDKs + lib
set -euo pipefail
source "$(dirname "$0")/_common.sh"

require_gpr_credentials

if [[ "${1:-}" == "--with-sdks" ]]; then
  echo "▶ Publicando SDKs de fabricantes..."
  gradle :vendor-sdks:publishAllPublicationsToGitHubPackagesRepository
fi

echo "▶ Publicando $LIB_GROUP:$LIB_ARTIFACT:$LIB_VERSION no GitHub Packages..."
gradle :smartx-rfid:testDebugUnitTest :smartx-rfid:publishReleasePublicationToGitHubPackagesRepository
echo "✓ Publicado: $(cfg '.repository.mavenUrl')"
