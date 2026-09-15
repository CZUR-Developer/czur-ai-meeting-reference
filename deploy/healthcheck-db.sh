#!/usr/bin/env bash
set -Eeuo pipefail

# 供外部数据库部署使用的只读健康检查。脚本只执行 SELECT 1，不修改数据库，
# 并且不打印连接串或密码。Compose 内的 PostgreSQL 使用自身的 pg_isready
# 健康检查，不需要重复调用本脚本。

DATABASE_URL="${DATABASE_URL:-}"
DATABASE_USER="${DATABASE_USER:-}"
DATABASE_PASSWORD="${DATABASE_PASSWORD:-}"
[[ -n "${DATABASE_URL}" ]] || { printf '[ERROR] 未设置 DATABASE_URL\n' >&2; exit 1; }
command -v psql >/dev/null 2>&1 || { printf '[ERROR] 未找到 psql\n' >&2; exit 1; }

case "${DATABASE_URL}" in
  jdbc:postgresql://*) PSQL_URL="${DATABASE_URL#jdbc:}" ;;
  postgresql://*|postgres://*) PSQL_URL="${DATABASE_URL}" ;;
  *) printf '[ERROR] DATABASE_URL 格式不受支持\n' >&2; exit 1 ;;
esac

export PGPASSWORD="${DATABASE_PASSWORD}"
args=("${PSQL_URL}" -v ON_ERROR_STOP=1 -Atc 'SELECT 1')
[[ -z "${DATABASE_USER}" ]] || args+=(--username="${DATABASE_USER}")
result="$(psql "${args[@]}")"
unset PGPASSWORD

if [[ "${result}" == "1" ]]; then
  printf '[INFO] 数据库健康检查通过\n'
else
  printf '[ERROR] 数据库健康检查返回异常结果\n' >&2
  exit 1
fi
