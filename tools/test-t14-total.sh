#!/usr/bin/env bash
set -Eeuo pipefail

# P2-T14-11 third slice: fresh V001..V012 deploy chain, one real PostgreSQL
# stop/start, production Outbox/expired-Reservation workers, and active-lease
# reclaim with stale-owner fencing across two JVMs, including formal finalization
# and ArtifactDeletionWorker workflow recovery.
# This script owns only the resources named by this run-id.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
LOG_LEVEL="${LOG_LEVEL:-INFO}"
declare -A LOG_RANK=([ERROR]=0 [WARN]=1 [INFO]=2 [DEBUG]=3)

log() {
  local level="$1"; shift
  local threshold="${LOG_RANK[${LOG_LEVEL}]:-2}"
  local current="${LOG_RANK[${level}]:-2}"
  if (( current <= threshold )); then printf '[%s] %s\n' "${level}" "$*"; fi
}
fail() { log ERROR "$*"; exit 1; }

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$-${RANDOM}"
RESOURCE_PREFIX="czur-t14-total-${RUN_ID}"
CONTAINER_NAME="${RESOURCE_PREFIX}-pg"
VOLUME_NAME="${RESOURCE_PREFIX}-data"
OWNERSHIP_LABEL_KEY="com.czur.ai-meeting.t14-total.run-id"
STATE_ROOT="${TMPDIR:-/tmp}"
STATE_ROOT="${STATE_ROOT%/}"
STATE_DIR=""
STATE_FILE=""
HOST_PORT=""
JDBC_URL=""
CONTAINER_CREATE_ATTEMPTED=0
VOLUME_CREATE_ATTEMPTED=0
CLEANUP_OK=1

is_safe_resource_name() {
  [[ "$1" =~ ^czur-t14-total-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-(pg|data)$ ]]
}
is_safe_state_dir() {
  [[ -n "${STATE_DIR}" && "${STATE_DIR}" == "${STATE_ROOT}"/czur-t14-total-state.* ]]
}
container_metadata() {
  docker container inspect --format \
    "{{.Id}}|{{index .Config.Labels \"${OWNERSHIP_LABEL_KEY}\"}}" "${CONTAINER_NAME}"
}
volume_metadata() {
  docker volume inspect --format \
    "{{.Name}}|{{index .Labels \"${OWNERSHIP_LABEL_KEY}\"}}" "${VOLUME_NAME}"
}
require_container_ownership() {
  local metadata container_id label
  metadata="$(container_metadata)" || fail "无法读取本次容器归属标签"
  container_id="${metadata%%|*}"; label="${metadata#*|}"
  [[ "${container_id}" =~ ^[0-9a-f]{64}$ && "${label}" == "${RUN_ID}" ]] \
    || fail "本次容器归属不匹配"
}
require_volume_ownership() {
  local metadata actual_name label
  metadata="$(volume_metadata)" || fail "无法读取本次数据卷归属标签"
  actual_name="${metadata%%|*}"; label="${metadata#*|}"
  [[ "${actual_name}" == "${VOLUME_NAME}" && "${label}" == "${RUN_ID}" ]] \
    || fail "本次数据卷归属不匹配"
}

cleanup() {
  local metadata container_id actual_name label
  set +e
  CLEANUP_OK=1
  if (( CONTAINER_CREATE_ATTEMPTED == 1 )); then
    if ! is_safe_resource_name "${CONTAINER_NAME}"; then
      log ERROR "拒绝清理不安全的容器名"; CLEANUP_OK=0
    elif metadata="$(container_metadata 2>/dev/null)"; then
      container_id="${metadata%%|*}"; label="${metadata#*|}"
      if [[ "${container_id}" =~ ^[0-9a-f]{64}$ && "${label}" == "${RUN_ID}" ]]; then
        docker rm -f -- "${container_id}" >/dev/null 2>&1 \
          && CONTAINER_CREATE_ATTEMPTED=0 \
          || { log ERROR "本次容器删除失败"; CLEANUP_OK=0; }
      else
        log ERROR "容器归属不匹配，拒绝删除"; CLEANUP_OK=0
      fi
    elif docker info >/dev/null 2>&1; then
      CONTAINER_CREATE_ATTEMPTED=0
    else
      log ERROR "无法确认本次容器是否已清理"; CLEANUP_OK=0
    fi
  fi
  if (( VOLUME_CREATE_ATTEMPTED == 1 )); then
    if ! is_safe_resource_name "${VOLUME_NAME}"; then
      log ERROR "拒绝清理不安全的数据卷名"; CLEANUP_OK=0
    elif metadata="$(volume_metadata 2>/dev/null)"; then
      actual_name="${metadata%%|*}"; label="${metadata#*|}"
      if [[ "${actual_name}" == "${VOLUME_NAME}" && "${label}" == "${RUN_ID}" ]]; then
        docker volume rm -- "${VOLUME_NAME}" >/dev/null 2>&1 \
          && VOLUME_CREATE_ATTEMPTED=0 \
          || { log ERROR "本次数据卷删除失败"; CLEANUP_OK=0; }
      else
        log ERROR "数据卷归属不匹配，拒绝删除"; CLEANUP_OK=0
      fi
    elif docker info >/dev/null 2>&1; then
      VOLUME_CREATE_ATTEMPTED=0
    else
      log ERROR "无法确认本次数据卷是否已清理"; CLEANUP_OK=0
    fi
  fi
  if [[ -n "${STATE_DIR}" ]]; then
    if is_safe_state_dir; then
      rm -rf -- "${STATE_DIR}" && STATE_DIR="" \
        || { log ERROR "本次状态目录删除失败"; CLEANUP_OK=0; }
    else
      log ERROR "拒绝清理不安全的状态目录"; CLEANUP_OK=0
    fi
  fi
  set -e
}
on_signal() { log WARN "收到 $1，开始精确清理"; exit 130; }

is_safe_resource_name "${CONTAINER_NAME}" || fail "容器名安全校验失败"
is_safe_resource_name "${VOLUME_NAME}" || fail "数据卷名安全校验失败"
for command in docker mvn; do command -v "${command}" >/dev/null || fail "缺少命令 ${command}"; done
docker info >/dev/null 2>&1 || fail "Docker daemon 不可用"
STATE_DIR="$(mktemp -d "${STATE_ROOT}/czur-t14-total-state.XXXXXX")"
is_safe_state_dir || fail "状态目录安全校验失败"
chmod 700 -- "${STATE_DIR}"
STATE_FILE="${STATE_DIR}/total-state.properties"
trap cleanup EXIT
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
trap 'on_signal HUP' HUP

if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then fail "容器名发生碰撞"; fi
if docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1; then fail "数据卷名发生碰撞"; fi

DB_NAME="t14_total"
DB_USER="t14_total_user"
DB_PASSWORD="${RESOURCE_PREFIX}-${RANDOM}"
log INFO "创建本次专属 PostgreSQL 16 资源（名称已限定本次 run-id）"
VOLUME_CREATE_ATTEMPTED=1
docker volume create --label "${OWNERSHIP_LABEL_KEY}=${RUN_ID}" -- "${VOLUME_NAME}" >/dev/null
require_volume_ownership
CONTAINER_CREATE_ATTEMPTED=1
docker run --detach --name "${CONTAINER_NAME}" \
  --label "${OWNERSHIP_LABEL_KEY}=${RUN_ID}" \
  --env "POSTGRES_DB=${DB_NAME}" --env "POSTGRES_USER=${DB_USER}" \
  --env "POSTGRES_PASSWORD=${DB_PASSWORD}" \
  --mount "type=volume,source=${VOLUME_NAME},target=/var/lib/postgresql/data" \
  --mount "type=bind,source=${REPO_ROOT}/deploy,target=/opt/czur-deploy,readonly" \
  --publish "127.0.0.1::5432" postgres:16 >/dev/null
require_container_ownership
require_volume_ownership

wait_for_postgres() {
  local attempt
  for (( attempt=1; attempt<=90; attempt++ )); do
    if docker exec "${CONTAINER_NAME}" pg_isready --quiet \
        --username "${DB_USER}" --dbname "${DB_NAME}"; then return 0; fi
    sleep 1
  done
  return 1
}
wait_for_loopback() {
  local attempt
  for (( attempt=1; attempt<=90; attempt++ )); do
    if { exec 3<>"/dev/tcp/127.0.0.1/${HOST_PORT}"; } 2>/dev/null; then
      exec 3>&-; return 0
    fi
    sleep 1
  done
  return 1
}
refresh_binding() {
  local binding
  require_container_ownership
  binding="$(docker port "${CONTAINER_NAME}" 5432/tcp)"
  [[ "${binding}" =~ ^127\.0\.0\.1:[0-9]+$ ]] || fail "数据库绑定不是随机 IPv4 loopback"
  HOST_PORT="${binding##*:}"
  [[ "${HOST_PORT}" != "55432" ]] || fail "拒绝保留端口 55432"
  JDBC_URL="jdbc:postgresql://127.0.0.1:${HOST_PORT}/${DB_NAME}"
}
wait_for_postgres || fail "PostgreSQL 未就绪"
OS_ID="$(docker exec "${CONTAINER_NAME}" sh -c '. /etc/os-release; printf "%s" "$ID"')"
[[ "${OS_ID}" == "debian" ]] || fail "数据库镜像不是 Debian"
POSTGRES_VERSION="$(docker exec "${CONTAINER_NAME}" postgres --version)"
[[ "${POSTGRES_VERSION}" == *" 16."* ]] || fail "数据库不是 PostgreSQL 16"
MOUNT_RW="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/opt/czur-deploy"}}{{.RW}}{{end}}{{end}}' "${CONTAINER_NAME}")"
[[ "${MOUNT_RW}" == "false" ]] || fail "deploy 挂载必须只读"
refresh_binding
wait_for_loopback || fail "宿主 loopback 未就绪"

log INFO "使用仓库 deploy/migrate.sh 应用完整 V001-V012 链"
docker exec --env "DATABASE_URL=postgresql://127.0.0.1:5432/${DB_NAME}" \
  --env "DATABASE_USER=${DB_USER}" --env "DATABASE_PASSWORD=${DB_PASSWORD}" \
  --env "LOG_LEVEL=${LOG_LEVEL}" "${CONTAINER_NAME}" /opt/czur-deploy/migrate.sh
MIGRATIONS="$(docker exec --env "PGPASSWORD=${DB_PASSWORD}" "${CONTAINER_NAME}" \
  psql --username "${DB_USER}" --dbname "${DB_NAME}" --tuples-only --no-align \
  --command "SELECT string_agg(version, ',' ORDER BY version) FROM schema_migrations;")"
EXPECTED="V001__initial_schema,V002__outbox_lease_fencing,V003__identity_entitlement,V004__meeting_event_envelope,V005__meeting_finalization_fact,V006__meeting_read_model,V007__meeting_manifest_revision,V008__durable_input_fact,V009__final_usage_fact,V010__workflow_step_fencing,V011__finalization_execution_artifact_ready,V012__artifact_deletion_plan"
[[ "${MIGRATIONS}" == "${EXPECTED}" ]] || fail "迁移登记不是精确 V001-V012 集合"

export REFERENCE_TEST_DATABASE_URL="${JDBC_URL}"
export REFERENCE_TEST_DATABASE_USER="${DB_USER}"
export REFERENCE_TEST_DATABASE_PASSWORD="${DB_PASSWORD}"
export T14_TOTAL_STATE_FILE="${STATE_FILE}"
log INFO "阶段 A：独立 Maven/JVM fresh-chain readiness（含最终收口与删除 lease）"
mvn -B --no-transfer-progress -f "${REPO_ROOT}/backend-reference/pom.xml" \
  '-Dtest=T14TotalGateIntegrationTest#prepareBeforeRestart' test
[[ -f "${STATE_FILE}" && ! -L "${STATE_FILE}" ]] || fail "阶段 A 未生成状态文件"
[[ "$(stat -c '%a' "${STATE_DIR}")" == "700" ]] || fail "状态目录权限不是 700"
[[ "$(stat -c '%a' "${STATE_FILE}")" == "600" ]] || fail "状态文件权限不是 600"
grep -qx 'phase=PREPARED' "${STATE_FILE}" || fail "阶段 A 状态不正确"

postmaster_start() {
  docker exec --env "PGPASSWORD=${DB_PASSWORD}" "${CONTAINER_NAME}" \
    psql --username "${DB_USER}" --dbname "${DB_NAME}" --tuples-only --no-align \
    --command "SELECT to_char(pg_postmaster_start_time() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"');"
}
BEFORE_START="$(postmaster_start)"
log INFO "对同一 PostgreSQL 容器执行真实 stop/start"
docker stop --time 30 "${CONTAINER_NAME}" >/dev/null
docker start "${CONTAINER_NAME}" >/dev/null
wait_for_postgres || fail "PostgreSQL 重启后未就绪"
refresh_binding
wait_for_loopback || fail "重启后宿主 loopback 未就绪"
AFTER_START="$(postmaster_start)"
[[ "${AFTER_START}" > "${BEFORE_START}" ]] || fail "postmaster start time 未变晚"

export REFERENCE_TEST_DATABASE_URL="${JDBC_URL}"
log INFO "阶段 B：独立 Maven/JVM restart verification（含最终收口与 ArtifactDeletionWorker）"
mvn -B --no-transfer-progress -f "${REPO_ROOT}/backend-reference/pom.xml" \
  '-Dtest=T14TotalGateIntegrationTest#verifyAfterRestart' test
grep -qx 'phase=VERIFIED' "${STATE_FILE}" || fail "阶段 B 状态不正确"

STATE_DIR_FOR_CHECK="${STATE_DIR}"
trap - EXIT
cleanup
(( CLEANUP_OK == 1 )) || fail "本次资源清理失败"
if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  fail "容器清理后仍存在"
fi
if docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1; then
  fail "数据卷清理后仍存在"
fi
[[ ! -e "${STATE_DIR_FOR_CHECK}" ]] || fail "状态目录清理后仍存在"
for (( attempt=1; attempt<=30; attempt++ )); do
  if ! { exec 3<>"/dev/tcp/127.0.0.1/${HOST_PORT}"; } 2>/dev/null; then
    log INFO "T14-11 fresh-chain/restart/worker/reclaim/finalization/deletion 第三片通过，随机端口已释放"
    exit 0
  fi
  exec 3>&-; exec 3<&-
  sleep 0.1
done
fail "随机端口清理后仍可连接"
