#!/usr/bin/env bash
set -Eeuo pipefail

# 本机参考后端数据库初始化入口。只执行仓库内唯一 V001，应用本身不会建表。
REPO_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${REFERENCE_ENV_FILE:-${REPO_DIR}/tools/reference-client.env}"
if [[ ! -f "${ENV_FILE}" ]]; then
  printf '[ERROR] 未找到配置文件：%s\n' "${ENV_FILE}" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a
exec bash "${REPO_DIR}/deploy/migrate.sh"
