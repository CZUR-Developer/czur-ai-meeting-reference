#!/usr/bin/env bash
set -Eeuo pipefail

# P2-T14-04-03 PostgreSQL 16 Outbox 栅栏总门禁。脚本只管理本次随机命名的
# 容器、数据卷和临时状态目录；禁止枚举、连接、停止或删除任何已有数据库资源。

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
RESOURCE_PREFIX="czur-t14-outbox-fence-${RUN_ID}"
CONTAINER_NAME="${RESOURCE_PREFIX}-pg"
VOLUME_NAME="${RESOURCE_PREFIX}-data"
OWNERSHIP_LABEL_KEY="com.czur.ai-meeting.t14-outbox-fence.run-id"
STATE_ROOT="${TMPDIR:-/tmp}"
STATE_ROOT="${STATE_ROOT%/}"
STATE_DIR=""
STATE_FILE=""
VOLUME_CREATE_ATTEMPTED=0
CONTAINER_CREATE_ATTEMPTED=0
CLEANUP_OK=1

is_safe_resource_name() {
  local value="$1"
  [[ "${value}" =~ ^czur-t14-outbox-fence-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-(pg|data)$ ]]
}

is_safe_state_dir() {
  [[ -n "${STATE_DIR}" && "${STATE_DIR}" == "${STATE_ROOT}"/czur-t14-outbox-fence-state.* ]]
}

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
  [[ "${label}" == "${RUN_ID}" ]] || fail "本次容器归属标签不匹配"
}

require_volume_ownership() {
  local metadata actual_name label
  metadata="$(volume_metadata)" || fail "无法读取本次数据卷的归属标签"
  actual_name="${metadata%%|*}"
  label="${metadata#*|}"
  [[ "${actual_name}" == "${VOLUME_NAME}" ]] || fail "本次数据卷名称异常"
  [[ "${label}" == "${RUN_ID}" ]] || fail "本次数据卷归属标签不匹配"
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
      elif ! docker info >/dev/null 2>&1; then
        log ERROR "Docker daemon 不可用，无法确认本次容器是否已清理"
        CLEANUP_OK=0
      else
        CONTAINER_CREATE_ATTEMPTED=0
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
      elif ! docker info >/dev/null 2>&1; then
        log ERROR "Docker daemon 不可用，无法确认本次数据卷是否已清理"
        CLEANUP_OK=0
      else
        VOLUME_CREATE_ATTEMPTED=0
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
      else
        log ERROR "本次状态目录删除失败"
        CLEANUP_OK=0
      fi
    else
      log ERROR "拒绝清理未通过 mktemp 前缀校验的状态目录"
      CLEANUP_OK=0
    fi
  fi
  set -e
}

on_signal() {
  local signal="$1"
  log WARN "收到 ${signal}，开始精确清理本次资源"
  exit 130
}

# 创建外部资源之前安装 trap。SIGKILL 无法触发 trap，README 提供严格基于
# 本次日志中精确名称和 ownership label 的人工恢复方式。
trap cleanup EXIT
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
trap 'on_signal HUP' HUP

is_safe_resource_name "${CONTAINER_NAME}" || fail "容器名安全校验失败"
is_safe_resource_name "${VOLUME_NAME}" || fail "数据卷名安全校验失败"

STATE_DIR="$(mktemp -d "${STATE_ROOT}/czur-t14-outbox-fence-state.XXXXXX")"
is_safe_state_dir || fail "状态目录安全校验失败"
chmod 700 -- "${STATE_DIR}"
STATE_FILE="${STATE_DIR}/outbox-fence-state.properties"

log INFO "本次专属资源 container=${CONTAINER_NAME} volume=${VOLUME_NAME} stateDir=${STATE_DIR}"

for command in docker mvn; do
  command -v "${command}" >/dev/null 2>&1 || fail "缺少命令：${command}"
done
docker info >/dev/null 2>&1 || fail "Docker daemon 不可用"

# 只检查本次随机精确名称；若极小概率碰撞则立即退出，绝不接管碰撞对象。
if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  fail "本次容器名发生碰撞，请重新运行"
fi
if docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1; then
  fail "本次数据卷名发生碰撞，请重新运行"
fi

DB_NAME="t14_outbox_fence"
DB_USER="t14_outbox_fence_user"
DB_PASSWORD="${RESOURCE_PREFIX}-${RANDOM}"

VOLUME_CREATE_ATTEMPTED=1
docker volume create \
  --label "${OWNERSHIP_LABEL_KEY}=${RUN_ID}" \
  -- "${VOLUME_NAME}" >/dev/null
require_volume_ownership

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
require_volume_ownership

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

# 每个独立 JVM 启动前都重新读取实际映射，不缓存、猜测或使用固定 55432。
refresh_loopback_binding() {
  local binding
  require_container_ownership
  binding="$(docker port "${CONTAINER_NAME}" 5432/tcp)"
  [[ "${binding}" =~ ^127\.0\.0\.1:[0-9]+$ ]] \
    || fail "数据库端口必须随机绑定到 IPv4 loopback"
  HOST_PORT="${binding##*:}"
  JDBC_URL="jdbc:postgresql://127.0.0.1:${HOST_PORT}/${DB_NAME}"
}

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

refresh_loopback_binding
wait_for_loopback_port || fail "数据库宿主 loopback 端口在限定时间内未就绪"

log INFO "使用仓库 deploy/migrate.sh 应用唯一 V001 基线（连接信息已隐藏）"
docker exec \
  --env "DATABASE_URL=postgresql://127.0.0.1:5432/${DB_NAME}" \
  --env "DATABASE_USER=${DB_USER}" \
  --env "DATABASE_PASSWORD=${DB_PASSWORD}" \
  --env "LOG_LEVEL=${LOG_LEVEL}" \
  "${CONTAINER_NAME}" /opt/czur-deploy/migrate.sh

MIGRATION_VERSIONS="$(docker exec --env "PGPASSWORD=${DB_PASSWORD}" \
  "${CONTAINER_NAME}" psql --username "${DB_USER}" --dbname "${DB_NAME}" \
  --tuples-only --no-align --command \
  "SELECT string_agg(version, ',' ORDER BY version) FROM schema_migrations;")"
EXPECTED_MIGRATION_VERSIONS="V001__initial_schema"
[[ "${MIGRATION_VERSIONS}" == "${EXPECTED_MIGRATION_VERSIONS}" ]] \
  || fail "迁移登记不是精确的 V001 集合"

export REFERENCE_TEST_DATABASE_USER="${DB_USER}"
export REFERENCE_TEST_DATABASE_PASSWORD="${DB_PASSWORD}"
export T14_OUTBOX_FENCE_STATE_FILE="${STATE_FILE}"
export T14_OUTBOX_FENCE_FIXTURE_PREFIX="t14-fence-${RUN_ID,,}"

run_phase() {
  local ordinal="$1"
  local method="$2"
  refresh_loopback_binding
  wait_for_loopback_port || fail "阶段 ${ordinal} 前宿主 loopback 端口未就绪"
  export REFERENCE_TEST_DATABASE_URL="${JDBC_URL}"
  log INFO "启动阶段 ${ordinal} 独立 Maven/JVM：${method}"
  mvn -B --no-transfer-progress \
    -f "${REPO_ROOT}/backend-reference/pom.xml" \
    "-Dtest=T14OutboxFenceIntegrationTest#${method}" test
}

run_phase A prepareLeasedFixturesInFirstJvm
[[ -f "${STATE_FILE}" && ! -L "${STATE_FILE}" ]] || fail "阶段 A 未生成普通状态文件"
[[ "$(stat -c '%a' "${STATE_DIR}")" == "700" ]] || fail "状态目录权限不是 700"
[[ "$(stat -c '%a' "${STATE_FILE}")" == "600" ]] || fail "状态文件权限不是 600"

run_phase B exerciseFencedLeasesInSecondJvm
[[ "$(stat -c '%a' "${STATE_FILE}")" == "600" ]] || fail "阶段 B 后状态文件权限不是 600"
if grep -q '^reclaimToken=' "${STATE_FILE}"; then
  fail "阶段 B 未从状态文件移除 lease token"
fi

run_phase C verifyDurableFenceStateInThirdJvm

STATE_DIR_FOR_CHECK="${STATE_DIR}"
trap - EXIT
cleanup
(( CLEANUP_OK == 1 )) || fail "本次资源清理失败，拒绝报告门禁通过"
if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  fail "本次容器清理后仍可见"
fi
if docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1; then
  fail "本次数据卷清理后仍可见"
fi
[[ -z "${STATE_DIR_FOR_CHECK}" || ! -e "${STATE_DIR_FOR_CHECK}" ]] \
  || fail "本次状态目录清理后仍可见"

log INFO "P2-T14-04-03 PostgreSQL 16 Outbox 栅栏总门禁通过"
