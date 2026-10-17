#!/usr/bin/env bash
set -Eeuo pipefail

# 只检查本机参考后端的存活和数据库 readiness；不打印配置、Token 或数据库地址。
BASE_URL="${REFERENCE_HTTP_BASE_URL:-http://127.0.0.1:8080}"
command -v curl >/dev/null 2>&1 || { printf '[ERROR] 未找到 curl\n' >&2; exit 1; }
for path in /internal/health/liveness /internal/health/readiness; do
  body="$(curl --fail --silent --show-error --max-time 5 \
    "${BASE_URL%/}${path}")" || {
      printf '[ERROR] 健康检查失败 path=%s\n' "${path}" >&2
      exit 1
    }
  [[ "${body}" == *'"status":"UP"'* ]] || {
    printf '[ERROR] 健康检查返回非 UP path=%s\n' "${path}" >&2
    exit 1
  }
  printf '[INFO] 健康检查通过 path=%s\n' "${path}"
done
