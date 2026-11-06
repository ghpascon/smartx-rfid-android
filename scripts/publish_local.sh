#!/usr/bin/env bash
# Publica a lib (e os SDKs) no Maven local (~/.m2) — útil para testar um app sem subir nada.
# No app, adicione mavenLocal() aos repositórios e use library.source = "maven-local" no config.json.
set -euo pipefail
source "$(dirname "$0")/_common.sh"

echo "▶ Publicando $LIB_GROUP:$LIB_ARTIFACT:$LIB_VERSION no Maven local..."
gradle :vendor-sdks:publishToMavenLocal :smartx-rfid:publishReleasePublicationToMavenLocal --quiet
echo "✓ Publicado em ~/.m2/repository/$(echo "$LIB_GROUP" | tr . /)/$LIB_ARTIFACT/$LIB_VERSION"
