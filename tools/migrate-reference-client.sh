#!/usr/bin/env bash
set -Eeuo pipefail

# 本机参考后端数据库初始化入口。只执行仓库内唯一 V001，应用本身不会建表。
# 默认读取仓外配置；不要为了省事把填好的配置复制回仓库，公开边界检查会判失败。
REPO_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${REFERENCE_ENV_FILE:-${HOME}/.config/ai-meeting/reference-client.env}"
if [[ ! -f "${ENV_FILE}" ]]; then
  printf '[ERROR] 未找到配置文件：%s\n' "${ENV_FILE}" >&2
  printf '[INFO] 先复制模板到仓外并填写，或用 REFERENCE_ENV_FILE 指定路径：\n' >&2
  printf '[INFO]   mkdir -p ~/.config/ai-meeting\n' >&2
  printf '[INFO]   cp tools/reference-client.env.example ~/.config/ai-meeting/reference-client.env\n' >&2
  exit 1
fi
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a
exec bash "${REPO_DIR}/deploy/migrate.sh"
