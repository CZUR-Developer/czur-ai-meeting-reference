#!/usr/bin/env bash
set -Eeuo pipefail

# 本机静态结果页入口；后端地址由环境注入，不写入源码或 Web 构建产物模板。
REPO_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
API_BASE_URL="${REFERENCE_HTTP_BASE_URL:-http://127.0.0.1:8080}"
WEB_BIND="${REFERENCE_WEB_BIND:-0.0.0.0}"
WEB_PORT="${REFERENCE_WEB_PORT:-8090}"
command -v node >/dev/null 2>&1 || { printf '[ERROR] 未找到 Node.js\n' >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { printf '[ERROR] 未找到 Python 3\n' >&2; exit 1; }

cd "${REPO_DIR}/web-reference"
# 地址直接交给构建生成 config.json：不再"先构建、后补文件"，构建也能给出明确报错。
REFERENCE_API_BASE_URL="${API_BASE_URL}" npm run build
exec python3 -m http.server "${WEB_PORT}" --bind "${WEB_BIND}" --directory dist
