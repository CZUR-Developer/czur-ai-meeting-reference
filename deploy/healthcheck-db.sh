#!/usr/bin/env bash
set -Eeuo pipefail

# 只读 MySQL 健康检查；只执行 SELECT 1，不修改数据库，
# 不输出连接串、账号或密码。目标库必须是已初始化的参考库。
DATABASE_URL="${DATABASE_URL:-}"
DATABASE_USER="${DATABASE_USER:-}"
DATABASE_PASSWORD="${DATABASE_PASSWORD:-}"
MYSQL_CLIENT="${MYSQL_CLIENT:-mysql}"

[[ -n "${DATABASE_URL}" ]] || { printf '[ERROR] 未设置 DATABASE_URL\n' >&2; exit 1; }
command -v "${MYSQL_CLIENT}" >/dev/null 2>&1   || { printf '[ERROR] 未找到 mysql 客户端\n' >&2; exit 1; }

mysql_url="${DATABASE_URL#jdbc:}"
if [[ "${mysql_url}" =~ ^mysql://([a-zA-Z0-9._-]+)(:([0-9]+))?/([a-zA-Z0-9_]+)(\?.*)?$ ]]; then
  mysql_host="${BASH_REMATCH[1]}"
  mysql_port="${BASH_REMATCH[3]:-3306}"
  mysql_database="${BASH_REMATCH[4]}"
else
  printf '[ERROR] DATABASE_URL 必须是 jdbc:mysql://主机[:端口]/库名\n' >&2
  exit 1
fi
[[ -n "${DATABASE_USER}" ]] || { printf '[ERROR] 未设置 DATABASE_USER\n' >&2; exit 1; }
(( 10#${mysql_port} >= 1 && 10#${mysql_port} <= 65535 ))   || { printf '[ERROR] MySQL 端口无效\n' >&2; exit 1; }

export MYSQL_PWD="${DATABASE_PASSWORD}"
trap 'unset MYSQL_PWD' EXIT
args=(--no-defaults --protocol=TCP --host="${mysql_host}" --port="${mysql_port}"
  --user="${DATABASE_USER}" --database="${mysql_database}"
  --default-character-set=utf8mb4 --batch --skip-column-names
  --init-command="SET SESSION time_zone = '+00:00'")
result="$("${MYSQL_CLIENT}" "${args[@]}" --execute 'SELECT 1')"

if [[ "${result}" == "1" ]]; then
  printf '[INFO] MySQL 数据库健康检查通过\n'
else
  printf '[ERROR] MySQL 健康检查返回异常结果\n' >&2
  exit 1
fi
