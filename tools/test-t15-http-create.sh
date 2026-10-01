#!/usr/bin/env bash
set -Eeuo pipefail

# 04-03-A：复用专属随机 PG16 门禁的归属标签、Surefire 计数与精确清理。
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${SCRIPT_DIR}/test-t15-http-health.sh" \
  realHttpCreateAndTrustedV2StartPreserveAsrOnlyFacts
