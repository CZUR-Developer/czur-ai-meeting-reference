#!/usr/bin/env bash
set -Eeuo pipefail

# 04-03-B0：复用随机 PG16 与本次专属持久 Artifact 目录验证匿名 Manifest GET。
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${SCRIPT_DIR}/test-t15-http-health.sh" \
  realHttpAnonymousManifestUsesFrozenJdbcFactsAndRevokesOnDelete
