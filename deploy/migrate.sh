#!/usr/bin/env bash
set -Eeuo pipefail

# 参考代码唯一数据库初始化入口，使用 MySQL 5.7 兼容基线。
# 只执行当前 V001，在调用方已存在的数据库新增固定 ai_trans_* 表。
# 不创建数据库/账号，不读写旧表，不关闭外键，不修复部分失败的初始化。
# MySQL DDL 隐式提交，因此只有整份 SQL 成功后才能写入基线登记；
# 启动应用仅检查基线，不应调用本脚本自动改变结构。

# 显式指定时加载与应用 Compose 相同的本机配置。无参数入口继续只读取已导出的环境，
# 不因当前目录存在另一个 .env 而悄悄切换数据库。配置文件由部署方维护，不能提交凭据。
if (( $# > 0 )); then
  [[ $# == 2 && "$1" == "--env-file" ]] || {
    printf '[ERROR] 用法：migrate.sh [--env-file 配置文件]\n' >&2
    exit 1
  }
  [[ -f "$2" ]] || { printf '[ERROR] 指定的配置文件不存在\n' >&2; exit 1; }
  set -a
  # shellcheck disable=SC1090
  source "$2"
  set +a
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MIGRATION_DIR="${MIGRATION_DIR:-${SCRIPT_DIR}/migrations}"
DATABASE_URL="${DATABASE_URL:-}"
DATABASE_USER="${DATABASE_USER:-}"
DATABASE_PASSWORD="${DATABASE_PASSWORD:-}"
MYSQL_CLIENT="${MYSQL_CLIENT:-mysql}"
LOG_LEVEL="${LOG_LEVEL:-INFO}"

declare -A LOG_RANK=([ERROR]=0 [WARN]=1 [INFO]=2 [DEBUG]=3)

# 日志不包含连接URL、用户、密码或SQL正文；DEBUG仅补充基线重跑信息。
log() {
  local level="$1"
  shift
  if (( ${LOG_RANK[${level}]:-2} <= ${LOG_RANK[${LOG_LEVEL}]:-2} )); then
    printf '[%s] %s\n' "${level}" "$*"
  fi
}

fail() {
  log ERROR "$*"
  exit 1
}

command -v "${MYSQL_CLIENT}" >/dev/null 2>&1 || fail "未找到 mysql，请安装 MySQL 客户端"
[[ -n "${DATABASE_USER}" ]] || fail "必须设置 DATABASE_USER"
[[ -d "${MIGRATION_DIR}" ]] || fail "数据库初始化目录不存在"

# 使用与应用相同的 JDBC URL 或 mysql:// URL；密码仅通过独立环境变量传入。
# CLI不消费JDBC查询参数，会话自身固定UTF-8/UTC。支持主机名/IPv4及可选端口。
# 地址或库名不合法时仅给出固定说明，不把原URL回显到日志。
mysql_url="${DATABASE_URL#jdbc:}"
if [[ "${mysql_url}" =~ ^mysql://([a-zA-Z0-9._-]+)(:([0-9]+))?/([a-zA-Z0-9_]+)(\?.*)?$ ]]; then
  mysql_host="${BASH_REMATCH[1]}"
  mysql_port="${BASH_REMATCH[3]:-3306}"
  mysql_database="${BASH_REMATCH[4]}"
else
  fail "DATABASE_URL 必须是 jdbc:mysql://主机[:端口]/库名 或 mysql://主机[:端口]/库名"
fi
(( 10#${mysql_port} >= 1 && 10#${mysql_port} <= 65535 )) || fail "MySQL 端口无效"

shopt -s nullglob
files=("${MIGRATION_DIR}"/*.sql)
(( ${#files[@]} == 1 )) || fail "参考方案必须且只能交付一份 V001 当前基线"
[[ "$(basename -- "${files[0]}")" == "V001__initial_schema.sql" ]] \
  || fail "当前基线必须是 V001__initial_schema.sql"

# 密码不写入命令行、文件或日志。退出时清理本脚本导出的客户端环境变量。
export MYSQL_PWD="${DATABASE_PASSWORD}"
trap 'unset MYSQL_PWD' EXIT
MYSQL_ARGS=(--no-defaults --protocol=TCP --host="${mysql_host}" --port="${mysql_port}"
  --user="${DATABASE_USER}" --database="${mysql_database}" --default-character-set=utf8mb4
  --batch --skip-column-names --init-command="SET SESSION time_zone = '+00:00'")

log INFO "检查 数据库当前初始化基线（目标地址已隐藏）"
registered_table="$("${MYSQL_CLIENT}" "${MYSQL_ARGS[@]}" --execute \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ai_trans_schema_migrations'")"
if [[ "${registered_table}" == "1" ]]; then
  applied="$("${MYSQL_CLIENT}" "${MYSQL_ARGS[@]}" --execute \
    "SELECT 1 FROM ai_trans_schema_migrations WHERE version='V001__initial_schema' LIMIT 1")"
  if [[ "${applied}" == "1" ]]; then
    log DEBUG "跳过已完整应用的 V001__initial_schema"
    log INFO "MySQL 当前初始化基线已应用，跳过"
    exit 0
  fi
fi

log INFO "执行 MySQL 5.7 V001__initial_schema"
# mysql未启用--force：任何DDL错误都会立即退出，后续登记不会执行。
# 若DDL中途失败，已经创建的表会留在原处；报告失败，不自动删除或修复。
if ! "${MYSQL_CLIENT}" "${MYSQL_ARGS[@]}" < "${files[0]}"; then
  fail "MySQL 初始化失败，未登记当前基线；部分DDL可能已提交，请核对隔离目标"
fi
"${MYSQL_CLIENT}" "${MYSQL_ARGS[@]}" --execute \
  "INSERT INTO ai_trans_schema_migrations(version) VALUES ('V001__initial_schema')"
log INFO "MySQL 当前初始化基线登记完成"
