#!/usr/bin/env bash
set -Eeuo pipefail

# 04-03-B：沿用随机 PG16 门禁，并把持久 Mock Artifact 置于本次私有目录。
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${SCRIPT_DIR}/test-t15-http-health.sh" \
  realHttpStopAdmissionAndMockWorkerPublishAsrOnlyResult
