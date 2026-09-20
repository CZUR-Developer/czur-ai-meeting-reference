#!/usr/bin/env bash
set -Eeuo pipefail

# P2-T13 PostgreSQL 真重启门禁。脚本只管理本次随机命名的容器、数据卷和
# mktemp 状态目录；不枚举、不挂载、不停止任何已有开发资源。

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
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

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$-${RANDOM}"
RESOURCE_PREFIX="czur-t13-restart-${RUN_ID}"
CONTAINER_NAME="${RESOURCE_PREFIX}-pg"
VOLUME_NAME="${RESOURCE_PREFIX}-data"
OWNERSHIP_LABEL_KEY="com.czur.ai-meeting.t13-restart.run-id"
STATE_ROOT="${TMPDIR:-/tmp}"
STATE_ROOT="${STATE_ROOT%/}"
STATE_DIR=""
STATE_FILE=""
VOLUME_CREATE_ATTEMPTED=0
CONTAINER_CREATE_ATTEMPTED=0
CLEANUP_OK=1

is_safe_resource_name() {
  local value="$1"
  [[ "${value}" =~ ^czur-t13-restart-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-(pg|data)$ ]]
}

is_safe_state_dir() {
  [[ -n "${STATE_DIR}" && "${STATE_DIR}" == "${STATE_ROOT}"/czur-t13-restart-state.* ]]
}

# 所有删除都先通过精确名称读取 Docker 元数据，再核对本次 run-id label。
# 容器额外返回不可复用的对象 ID；删除 ID 可避免名称在校验后被其他容器复用。
container_metadata() {
  docker container inspect --format \
    "{{.Id}}|{{index .Config.Labels \"${OWNERSHIP_LABEL_KEY}\"}}" \
    "${CONTAINER_NAME}"
}

volume_metadata() {
  docker volume inspect --format \
    "{{.Name}}|{{index .Labels \"${OWNERSHIP_LABEL_KEY}\"}}" \
    "${VOLUME_NAME}"
}

require_container_ownership() {
  local metadata container_id label
  metadata="$(container_metadata)" || fail "无法读取本次容器的归属标签"
  container_id="${metadata%%|*}"
  label="${metadata#*|}"
  [[ "${container_id}" =~ ^[0-9a-f]{64}$ ]] || fail "本次容器 ID 格式异常"
  [[ "${label}" == "${RUN_ID}" ]] \
    || fail "本次容器归属标签不匹配，拒绝继续使用"
}

require_volume_ownership() {
  local metadata actual_name label
  metadata="$(volume_metadata)" || fail "无法读取本次数据卷的归属标签"
  actual_name="${metadata%%|*}"
  label="${metadata#*|}"
  [[ "${actual_name}" == "${VOLUME_NAME}" ]] || fail "本次数据卷名称异常"
  [[ "${label}" == "${RUN_ID}" ]] \
    || fail "本次数据卷归属标签不匹配，拒绝继续使用"
}

cleanup() {
  local metadata container_id actual_name label
  set +e
  CLEANUP_OK=1
  if (( CONTAINER_CREATE_ATTEMPTED == 1 )); then
    if is_safe_resource_name "${CONTAINER_NAME}"; then
      if metadata="$(container_metadata 2>/dev/null)"; then
        container_id="${metadata%%|*}"
        label="${metadata#*|}"
        if [[ "${container_id}" =~ ^[0-9a-f]{64}$ && "${label}" == "${RUN_ID}" ]]; then
          if docker rm -f -- "${container_id}" >/dev/null 2>&1; then
            CONTAINER_CREATE_ATTEMPTED=0
          else
            log ERROR "本次容器删除失败 container=${CONTAINER_NAME}"
            CLEANUP_OK=0
          fi
        else
          log ERROR "容器归属标签不匹配，拒绝删除 container=${CONTAINER_NAME}"
          CLEANUP_OK=0
        fi
      elif docker info >/dev/null 2>&1; then
        # 创建命令可能在对象落盘前失败；daemon 可访问且精确 inspect 不存在即已清理。
        CONTAINER_CREATE_ATTEMPTED=0
      else
        log ERROR "Docker daemon 不可用，无法确认本次容器是否已清理"
        CLEANUP_OK=0
      fi
    else
      log ERROR "拒绝清理未通过精确前缀校验的容器名"
      CLEANUP_OK=0
    fi
  fi
  if (( VOLUME_CREATE_ATTEMPTED == 1 )); then
    if is_safe_resource_name "${VOLUME_NAME}"; then
      if metadata="$(volume_metadata 2>/dev/null)"; then
        actual_name="${metadata%%|*}"
        label="${metadata#*|}"
        if [[ "${actual_name}" == "${VOLUME_NAME}" && "${label}" == "${RUN_ID}" ]]; then
          if docker volume rm -- "${VOLUME_NAME}" >/dev/null 2>&1; then
            VOLUME_CREATE_ATTEMPTED=0
          else
            log ERROR "本次数据卷删除失败 volume=${VOLUME_NAME}"
            CLEANUP_OK=0
          fi
        else
          log ERROR "数据卷归属标签不匹配，拒绝删除 volume=${VOLUME_NAME}"
          CLEANUP_OK=0
        fi
      elif docker info >/dev/null 2>&1; then
        VOLUME_CREATE_ATTEMPTED=0
      else
        log ERROR "Docker daemon 不可用，无法确认本次数据卷是否已清理"
        CLEANUP_OK=0
      fi
    else
      log ERROR "拒绝清理未通过精确前缀校验的数据卷名"
      CLEANUP_OK=0
    fi
  fi
  if [[ -n "${STATE_DIR}" ]]; then
    if is_safe_state_dir; then
      if rm -rf -- "${STATE_DIR}"; then
        STATE_DIR=""
        STATE_FILE=""
      else
        CLEANUP_OK=0
      fi
    else
      log ERROR "拒绝清理未通过 mktemp 前缀校验的状态目录"
      CLEANUP_OK=0
    fi
  fi
  set -e
  return 0
}

on_signal() {
  local signal="$1"
  log WARN "收到 ${signal}，开始精确清理本次资源"
  exit 130
}

# 在创建任何外部资源前安装 trap。SIGKILL 无法触发 trap，README 提供基于本次
# 日志中精确名称的人工恢复命令，禁止使用模糊匹配或 prune。
trap cleanup EXIT
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
trap 'on_signal HUP' HUP

is_safe_resource_name "${CONTAINER_NAME}" || fail "容器名安全校验失败"
is_safe_resource_name "${VOLUME_NAME}" || fail "数据卷名安全校验失败"

STATE_DIR="$(mktemp -d "${STATE_ROOT}/czur-t13-restart-state.XXXXXX")"
is_safe_state_dir || fail "状态目录安全校验失败"
chmod 700 -- "${STATE_DIR}"
STATE_FILE="${STATE_DIR}/restart-state.properties"

log INFO "本次专属资源 container=${CONTAINER_NAME} volume=${VOLUME_NAME} stateDir=${STATE_DIR}"

for command in docker mvn; do
  command -v "${command}" >/dev/null 2>&1 || fail "缺少命令：${command}"
done
docker info >/dev/null 2>&1 || fail "Docker daemon 不可用"

# 只检查本次随机生成的精确名称，碰撞时立即退出且绝不删除碰撞资源。
if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  fail "本次容器名发生碰撞，请重新运行"
fi
if docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1; then
  fail "本次数据卷名发生碰撞，请重新运行"
fi

DB_NAME="t13_restart"
DB_USER="t13_restart_user"
DB_PASSWORD="${RESOURCE_PREFIX}-${RANDOM}"

VOLUME_CREATE_ATTEMPTED=1
docker volume create \
  --label "${OWNERSHIP_LABEL_KEY}=${RUN_ID}" \
  -- "${VOLUME_NAME}" >/dev/null
require_volume_ownership

# postgres:16 是官方 Debian 变体；启动后还会从 /etc/os-release 做运行时确认。
# deploy 整目录只读挂载，以便直接复用仓库 migrate.sh 的排序和登记实现。
CONTAINER_CREATE_ATTEMPTED=1
docker run --detach \
  --name "${CONTAINER_NAME}" \
  --label "${OWNERSHIP_LABEL_KEY}=${RUN_ID}" \
  --env "POSTGRES_DB=${DB_NAME}" \
  --env "POSTGRES_USER=${DB_USER}" \
  --env "POSTGRES_PASSWORD=${DB_PASSWORD}" \
  --mount "type=volume,source=${VOLUME_NAME},target=/var/lib/postgresql/data" \
  --mount "type=bind,source=${REPO_ROOT}/deploy,target=/opt/czur-deploy,readonly" \
  --publish "127.0.0.1::5432" \
  postgres:16 >/dev/null
require_container_ownership

wait_for_postgres() {
  local attempt
  for (( attempt=1; attempt<=90; attempt++ )); do
    if docker exec "${CONTAINER_NAME}" \
        pg_isready --quiet --username "${DB_USER}" --dbname "${DB_NAME}"; then
      log DEBUG "PostgreSQL readiness 通过 attempt=${attempt}"
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_postgres || fail "PostgreSQL 在限定时间内未就绪"

OS_ID="$(docker exec "${CONTAINER_NAME}" sh -c \
  '. /etc/os-release; printf "%s" "$ID"')"
[[ "${OS_ID}" == "debian" ]] || fail "测试镜像不是 Debian 变体"
POSTGRES_VERSION="$(docker exec "${CONTAINER_NAME}" postgres --version)"
[[ "${POSTGRES_VERSION}" == *" 16."* ]] || fail "测试数据库不是 PostgreSQL 16"
MOUNT_RW="$(docker inspect --format \
  '{{range .Mounts}}{{if eq .Destination "/opt/czur-deploy"}}{{.RW}}{{end}}{{end}}' \
  "${CONTAINER_NAME}")"
[[ "${MOUNT_RW}" == "false" ]] || fail "deploy 挂载必须为只读"
log INFO "数据库镜像已确认 image=postgres:16 os=debian deployMount=readonly"

HOST_PORT=""
JDBC_URL=""

# 动态 HostPort 在部分 Docker daemon 上会在 stop/start 后重新分配。每一阶段
# 都从同一容器的精确 5432/tcp 映射重新读取实际 loopback 入口；这不会改变
# 容器、数据卷或 PostgreSQL 数据目录。
refresh_loopback_binding() {
  local binding
  binding="$(docker port "${CONTAINER_NAME}" 5432/tcp)"
  [[ "${binding}" =~ ^127\.0\.0\.1:[0-9]+$ ]] \
    || fail "数据库端口必须随机绑定到 IPv4 loopback"
  HOST_PORT="${binding##*:}"
  JDBC_URL="jdbc:postgresql://127.0.0.1:${HOST_PORT}/${DB_NAME}"
}

refresh_loopback_binding

# 容器内 pg_isready 只能证明 postmaster 已监听容器网络；docker start 后宿主
# loopback 的端口转发可能稍晚建立。Maven/Hikari 运行前必须同时证明宿主 TCP
# 入口可连接，避免把转发竞态误报为数据库持久性失败。
wait_for_loopback_port() {
  local attempt
  for (( attempt=1; attempt<=90; attempt++ )); do
    if { exec 3<>"/dev/tcp/127.0.0.1/${HOST_PORT}"; } 2>/dev/null; then
      exec 3>&-
      log DEBUG "宿主 loopback 数据库端口就绪 attempt=${attempt}"
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_loopback_port || fail "数据库宿主 loopback 端口在限定时间内未就绪"

log INFO "使用仓库 deploy/migrate.sh 应用并登记 V001-V006（连接信息已隐藏）"
docker exec \
  --env "DATABASE_URL=postgresql://127.0.0.1:5432/${DB_NAME}" \
  --env "DATABASE_USER=${DB_USER}" \
  --env "DATABASE_PASSWORD=${DB_PASSWORD}" \
  --env "LOG_LEVEL=${LOG_LEVEL}" \
  "${CONTAINER_NAME}" /opt/czur-deploy/migrate.sh

MIGRATION_COUNT="$(docker exec --env "PGPASSWORD=${DB_PASSWORD}" \
  "${CONTAINER_NAME}" psql --username "${DB_USER}" --dbname "${DB_NAME}" \
  --tuples-only --no-align --command \
  "SELECT count(*) FROM schema_migrations WHERE version LIKE 'V00%';")"
[[ "${MIGRATION_COUNT}" == "6" ]] || fail "迁移登记数量不是预期的 6"

postmaster_start_time() {
  docker exec --env "PGPASSWORD=${DB_PASSWORD}" \
    "${CONTAINER_NAME}" psql --username "${DB_USER}" --dbname "${DB_NAME}" \
    --tuples-only --no-align --command \
    "SELECT to_char(pg_postmaster_start_time() AT TIME ZONE 'UTC', \
     'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"');"
}

export REFERENCE_TEST_DATABASE_URL="${JDBC_URL}"
export REFERENCE_TEST_DATABASE_USER="${DB_USER}"
export REFERENCE_TEST_DATABASE_PASSWORD="${DB_PASSWORD}"
export T13_RESTART_STATE_FILE="${STATE_FILE}"

BEFORE_START="$(postmaster_start_time)"
log INFO "重启前 postmasterStart=${BEFORE_START}"
log INFO "启动第一阶段 Maven/JVM：prepareBeforeRestart"
mvn -B --no-transfer-progress \
  -f "${REPO_ROOT}/backend-reference/pom.xml" \
  '-Dtest=T13PostgresRestartIntegrationTest#prepareBeforeRestart' test

[[ -f "${STATE_FILE}" ]] || fail "第一阶段未生成重启状态文件"
[[ "$(stat -c '%a' "${STATE_DIR}")" == "700" ]] || fail "状态目录权限不是 700"
[[ "$(stat -c '%a' "${STATE_FILE}")" == "600" ]] || fail "状态文件权限不是 600"

log INFO "停止并重新启动同一 PostgreSQL 容器"
docker stop --time 30 "${CONTAINER_NAME}" >/dev/null
docker start "${CONTAINER_NAME}" >/dev/null
wait_for_postgres || fail "PostgreSQL 重启后未在限定时间内就绪"
refresh_loopback_binding
export REFERENCE_TEST_DATABASE_URL="${JDBC_URL}"
wait_for_loopback_port || fail "PostgreSQL 重启后宿主 loopback 端口未就绪"

AFTER_START="$(postmaster_start_time)"
log INFO "重启后 postmasterStart=${AFTER_START}"
[[ "${AFTER_START}" > "${BEFORE_START}" ]] \
  || fail "postmaster start time 未严格变晚"

log INFO "启动第二阶段 Maven/JVM：verifyAfterRestart"
mvn -B --no-transfer-progress \
  -f "${REPO_ROOT}/backend-reference/pom.xml" \
  '-Dtest=T13PostgresRestartIntegrationTest#verifyAfterRestart' test

log INFO "业务断言已通过，开始精确清理本次资源"
STATE_DIR_FOR_CHECK="${STATE_DIR}"
cleanup
(( CLEANUP_OK == 1 )) || fail "至少一个本次专属资源清理失败"

if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  fail "本次专属容器清理后仍然存在"
fi
if docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1; then
  fail "本次专属数据卷清理后仍然存在"
fi
[[ ! -e "${STATE_DIR_FOR_CHECK}" ]] \
  || fail "本次状态目录清理后仍然存在"

trap - EXIT INT TERM HUP
log INFO "P2-T13 PostgreSQL 真重启门禁通过，且本次资源已全部移除"
