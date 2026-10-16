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
npm run build
python3 - "${API_BASE_URL}" dist/config.json <<'PY'
import json
import sys
from pathlib import Path

Path(sys.argv[2]).write_text(json.dumps({"apiBaseUrl": sys.argv[1]}, ensure_ascii=False), encoding="utf-8")
PY
exec python3 -m http.server "${WEB_PORT}" --bind "${WEB_BIND}" --directory dist
