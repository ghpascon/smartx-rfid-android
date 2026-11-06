#!/usr/bin/env bash
# Regenera vendor-sdks/repo (repositório Maven dos SDKs de fabricantes) a partir de vendor-sdks/aars.
# Rode sempre que adicionar/atualizar um AAR/JAR em vendor-sdks/aars (e ajustar config.json -> vendorSdks).
#   ./scripts/publish_sdks.sh            -> atualiza vendor-sdks/repo (versionado no git)
#   ./scripts/publish_sdks.sh --remote   -> também publica no GitHub Packages
set -euo pipefail
source "$(dirname "$0")/_common.sh"

echo "▶ Gerando vendor-sdks/repo..."
gradle :vendor-sdks:publishAllPublicationsToLocalRepoRepository --quiet
echo "✓ vendor-sdks/repo atualizado"

if [[ "${1:-}" == "--remote" ]]; then
  require_gpr_credentials
  echo "▶ Publicando SDKs no GitHub Packages (versões já existentes serão rejeitadas)..."
  gradle :vendor-sdks:publishAllPublicationsToGitHubPackagesRepository
  echo "✓ SDKs publicados"
fi
