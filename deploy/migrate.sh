#!/usr/bin/env bash
set -Eeuo pipefail

# 参考数据库迁移入口。脚本只执行仓库中的版本化 SQL，不创建账号、不写入
# 默认用户，也不会把 DATABASE_URL、密码或其他 Secret 输出到日志。

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MIGRATION_DIR="${MIGRATION_DIR:-${SCRIPT_DIR}/migrations}"
DATABASE_URL="${DATABASE_URL:-}"
DATABASE_USER="${DATABASE_USER:-}"
DATABASE_PASSWORD="${DATABASE_PASSWORD:-}"
LOG_LEVEL="${LOG_LEVEL:-INFO}"

declare -A LOG_RANK=(
  [ERROR]=0
  [WARN]=1
  [INFO]=2
  [DEBUG]=3
)

log() {
  local level="$1"
  shift
  local threshold="${LOG_RANK[${LOG_LEVEL}]:-2}"
  local current="${LOG_RANK[${level}]:-2}"
  if (( current <= threshold )); then
    printf '[%s] %s\n' "${level}" "$*"
  fi
}

fail() {
  log ERROR "$*"
  exit 1
}

command -v psql >/dev/null 2>&1 || fail "未找到 psql，请安装 PostgreSQL 客户端"
[[ -n "${DATABASE_URL}" ]] || fail "必须设置 DATABASE_URL（支持 postgresql:// 或 jdbc:postgresql://）"
[[ -d "${MIGRATION_DIR}" ]] || fail "迁移目录不存在：${MIGRATION_DIR}"

# 应用服务可使用 JDBC URL；psql 需要去掉 jdbc: 前缀。URL 可能携带密码，
# 这里只在变量中传递，绝不打印完整地址。
case "${DATABASE_URL}" in
  jdbc:postgresql://*) PSQL_URL="${DATABASE_URL#jdbc:}" ;;
  postgresql://*|postgres://*) PSQL_URL="${DATABASE_URL}" ;;
  *) fail "DATABASE_URL 必须是 postgresql:// 或 jdbc:postgresql:// 地址" ;;
esac

export PGPASSWORD="${DATABASE_PASSWORD}"
PSQL_ARGS=("${PSQL_URL}" -v ON_ERROR_STOP=1)
if [[ -n "${DATABASE_USER}" ]]; then
  PSQL_ARGS+=(--username="${DATABASE_USER}")
fi

log INFO "开始数据库迁移（目标地址已隐藏）"
psql "${PSQL_ARGS[@]}" <<'SQL'
CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
SQL

shopt -s nullglob
files=("${MIGRATION_DIR}"/V*.sql)
(( ${#files[@]} > 0 )) || fail "迁移目录中没有 V*.sql 文件"

for file in "${files[@]}"; do
  version="$(basename -- "${file}" .sql)"
  applied="$(psql "${PSQL_ARGS[@]}" -Atc "SELECT 1 FROM schema_migrations WHERE version = '$version' LIMIT 1")"
  if [[ "${applied}" == "1" ]]; then
    log DEBUG "跳过已应用迁移 ${version}"
    continue
  fi

  log INFO "执行迁移 ${version}"
  psql "${PSQL_ARGS[@]}" -f "${file}"
  psql "${PSQL_ARGS[@]}" -c \
    "INSERT INTO schema_migrations(version) VALUES ('${version}') ON CONFLICT (version) DO NOTHING"
done

unset PGPASSWORD
log INFO "数据库迁移完成"
