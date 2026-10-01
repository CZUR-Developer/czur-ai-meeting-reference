#!/usr/bin/env bash
set -Eeuo pipefail

# P2-T15-15-04-01：只操作本次带精确归属标签的 PostgreSQL 16 容器和卷。
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$-${RANDOM}"
RESOURCE_PREFIX="czur-t15-health-${RUN_ID}"
CONTAINER_NAME="${RESOURCE_PREFIX}-pg"
VOLUME_NAME="${RESOURCE_PREFIX}-data"
LABEL_KEY='com.czur.ai-meeting.t15-health.run-id'
STATE_DIR=''
CONTAINER_ID=''
CREATED_CONTAINER=0
CREATED_VOLUME=0
STAGE='preflight'
SUREFIRE_COUNTS='NA'
CONTAINER_CLEANUP='NOT_CREATED'
VOLUME_CLEANUP='NOT_CREATED'
STATE_CLEANUP='NOT_CREATED'

fail() { printf '[ERROR] %s\n' "$*" >&2; exit 1; }
info() { printf '[INFO] %s\n' "$*"; }

cleanup() {
  local metadata id label removable failed=0 docker_available=1
  if (( CREATED_CONTAINER || CREATED_VOLUME )); then
    docker info >/dev/null 2>&1 || docker_available=0
  fi
  if (( CREATED_CONTAINER )); then
    if (( ! docker_available )); then
      CONTAINER_CLEANUP='UNKNOWN_DAEMON'
      failed=1
    else
      CONTAINER_CLEANUP='ABSENT'
      metadata="$(docker container inspect --format \
        '{{.Id}}|{{index .Config.Labels "com.czur.ai-meeting.t15-health.run-id"}}' \
        "${CONTAINER_NAME}" 2>/dev/null || true)"
      if [[ -n "${metadata}" ]]; then
        id="${metadata%%|*}"
        label="${metadata#*|}"
        if [[ "${id}" =~ ^[0-9a-f]{64}$ && "${label}" == "${RUN_ID}" \
            && ( -z "${CONTAINER_ID}" || "${id}" == "${CONTAINER_ID}" ) ]]; then
          removable=1
          if [[ "$(docker container inspect --format '{{.State.Paused}}' "${id}")" == true ]]; then
            docker unpause -- "${id}" >/dev/null 2>&1 || removable=0
          fi
          if (( removable )) && docker rm -f -- "${id}" >/dev/null 2>&1 \
              && ! docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
            CONTAINER_CLEANUP='REMOVED'
          else
            CONTAINER_CLEANUP='FAILED'
            failed=1
          fi
        else
          CONTAINER_CLEANUP='REFUSED_OWNER_MISMATCH'
          failed=1
        fi
      fi
    fi
  fi
  if (( CREATED_VOLUME )); then
    if (( ! docker_available )); then
      VOLUME_CLEANUP='UNKNOWN_DAEMON'
      failed=1
    else
      VOLUME_CLEANUP='ABSENT'
      metadata="$(docker volume inspect --format \
        '{{.Name}}|{{index .Labels "com.czur.ai-meeting.t15-health.run-id"}}' \
        "${VOLUME_NAME}" 2>/dev/null || true)"
      if [[ -n "${metadata}" ]]; then
        label="${metadata#*|}"
        if [[ "${metadata%%|*}" == "${VOLUME_NAME}" && "${label}" == "${RUN_ID}" ]]; then
          if docker volume rm -- "${VOLUME_NAME}" >/dev/null 2>&1 \
              && ! docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1; then
            VOLUME_CLEANUP='REMOVED'
          else
            VOLUME_CLEANUP='FAILED'
            failed=1
          fi
        else
          VOLUME_CLEANUP='REFUSED_OWNER_MISMATCH'
          failed=1
        fi
      fi
    fi
  fi
  if [[ -n "${STATE_DIR}" ]]; then
    if [[ "${STATE_DIR}" == /tmp/czur-t15-health-state.* ]]; then
      if [[ -d "${STATE_DIR}" ]]; then
        if rm -rf -- "${STATE_DIR}" && [[ ! -e "${STATE_DIR}" ]]; then
          STATE_CLEANUP='REMOVED'
        else
          STATE_CLEANUP='FAILED'
          failed=1
        fi
      else
        STATE_CLEANUP='ABSENT'
      fi
    else
      STATE_CLEANUP='REFUSED_UNSAFE_PATH'
      failed=1
    fi
  fi
  return "${failed}"
}

capture_surefire_counts() {
  local raw=''
  if [[ -n "${STATE_DIR}" && -f "${STATE_DIR}/test.log" ]]; then
    raw="$(rg -o 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' \
      "${STATE_DIR}/test.log" 2>/dev/null | tail -1 || true)"
  fi
  if [[ "${raw}" =~ ^Tests[[:space:]]run:[[:space:]]([0-9]+),[[:space:]]Failures:[[:space:]]([0-9]+),[[:space:]]Errors:[[:space:]]([0-9]+),[[:space:]]Skipped:[[:space:]]([0-9]+)$ ]]; then
    SUREFIRE_COUNTS="${BASH_REMATCH[1]},${BASH_REMATCH[2]},${BASH_REMATCH[3]},${BASH_REMATCH[4]}"
  fi
}

on_exit() {
  local result=$? cleanup_failed=0 diagnostic_dir=''
  trap - EXIT
  set +e
  capture_surefire_counts
  cleanup || cleanup_failed=1
  if (( result == 0 && cleanup_failed == 0 )); then
    info '隔离资源已清理'
    exit 0
  fi
  if (( result == 0 )); then result=1; fi
  diagnostic_dir="$(mktemp -d /tmp/czur-t15-health-diagnostic.XXXXXX)"
  if [[ -n "${diagnostic_dir}" ]]; then
    chmod 700 -- "${diagnostic_dir}"
    (umask 077; printf 'stage=%s\nexit_code=%s\nsurefire_counts=%s\ncontainer_cleanup=%s\nvolume_cleanup=%s\nstate_cleanup=%s\n' \
      "${STAGE}" "${result}" "${SUREFIRE_COUNTS}" "${CONTAINER_CLEANUP}" \
      "${VOLUME_CLEANUP}" "${STATE_CLEANUP}" >"${diagnostic_dir}/summary.txt")
    chmod 600 -- "${diagnostic_dir}/summary.txt"
    info "失败诊断仅保留白名单摘要：${diagnostic_dir}/summary.txt"
  else
    printf '[ERROR] 无法建立私有失败诊断目录\n' >&2
  fi
  exit "${result}"
}
trap on_exit EXIT
trap 'exit 130' INT TERM HUP

command -v docker >/dev/null || fail '缺少 docker'
command -v mvn >/dev/null || fail '缺少 mvn'
command -v python3 >/dev/null || fail '缺少随机端口选择所需的 python3'
TEST_SELECTOR="${1:-internalHealthUsesSameJdbcAssemblyAcrossDatabaseOutageAndRecovery}"
case "${TEST_SELECTOR}" in
  internalHealthUsesSameJdbcAssemblyAcrossDatabaseOutageAndRecovery|\
  boundedNetworkOwnerCleansUniquePoolAfterBindFailureAndNormalStop|\
  pausedDatabaseReadinessUsesPgjdbcSocketBoundAndRecoversOnSamePool|\
  closedAssemblyUsesRealQueryStopAndT14DeletionPorts|\
  realHttpCreateAndTrustedV2StartPreserveAsrOnlyFacts|\
  realHttpStopAdmissionAndMockWorkerPublishAsrOnlyResult) ;;
  *) fail '不支持的定向测试选择器' ;;
esac
docker info >/dev/null || fail 'Docker daemon 不可用'
[[ "${CONTAINER_NAME}" =~ ^czur-t15-health-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-pg$ ]] \
  || fail '容器名不安全'
[[ "${VOLUME_NAME}" =~ ^czur-t15-health-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-data$ ]] \
  || fail '数据卷名不安全'
docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1 && fail '容器名碰撞'
docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1 && fail '卷名碰撞'

STAGE='setup'
STATE_DIR="$(mktemp -d /tmp/czur-t15-health-state.XXXXXX)"
chmod 700 -- "${STATE_DIR}"
if [[ "${TEST_SELECTOR}" == realHttpStopAdmissionAndMockWorkerPublishAsrOnlyResult ]]; then
  ARTIFACT_ROOT="${STATE_DIR}/artifacts"
  mkdir -m 700 -- "${ARTIFACT_ROOT}"
  export T15_HEALTH_GATE_ARTIFACT_ROOT="${ARTIFACT_ROOT}"
fi
DB_NAME='t15_health_gate'
DB_USER='t15_health_user'
DB_PASSWORD="${RESOURCE_PREFIX}-${RANDOM}"
DB_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
[[ "${DB_PORT}" =~ ^[0-9]+$ && "${DB_PORT}" != 55432 ]] || fail '随机数据库端口非法'
info "专属资源 container=${CONTAINER_NAME} volume=${VOLUME_NAME}"
CREATED_VOLUME=1
docker volume create --label "${LABEL_KEY}=${RUN_ID}" -- "${VOLUME_NAME}" >/dev/null
CREATED_CONTAINER=1
CONTAINER_ID="$(docker run --detach --name "${CONTAINER_NAME}" \
  --label "${LABEL_KEY}=${RUN_ID}" \
  --env "POSTGRES_DB=${DB_NAME}" --env "POSTGRES_USER=${DB_USER}" \
  --env "POSTGRES_PASSWORD=${DB_PASSWORD}" \
  --mount "type=volume,source=${VOLUME_NAME},target=/var/lib/postgresql/data" \
  --publish "127.0.0.1:${DB_PORT}:5432" postgres:16)"
[[ "${CONTAINER_ID}" =~ ^[0-9a-f]{64}$ ]] || fail '容器 ID 非预期格式'

for (( attempt=1; attempt<=90; attempt++ )); do
  if docker exec "${CONTAINER_ID}" pg_isready --quiet \
      --username "${DB_USER}" --dbname "${DB_NAME}"; then
    break
  fi
  sleep 1
done
(( attempt <= 90 )) || fail 'PostgreSQL 16 未就绪'
[[ "$(docker exec "${CONTAINER_ID}" postgres --version)" == *' 16.'* ]] \
  || fail '数据库不是 PostgreSQL 16'
DB_BINDING="$(docker port "${CONTAINER_ID}" 5432/tcp)"
[[ "${DB_BINDING}" =~ ^127\.0\.0\.1:[0-9]+$ ]] || fail '数据库未绑定随机 loopback'
[[ "${DB_BINDING}" == "127.0.0.1:${DB_PORT}" ]] || fail '数据库绑定端口漂移'

export REFERENCE_TEST_DATABASE_URL="jdbc:postgresql://${DB_BINDING}/${DB_NAME}"
export REFERENCE_TEST_DATABASE_USER="${DB_USER}"
export REFERENCE_TEST_DATABASE_PASSWORD="${DB_PASSWORD}"
export T15_HEALTH_GATE_CONTAINER_ID="${CONTAINER_ID}"
export T15_HEALTH_GATE_RUN_ID="${RUN_ID}"

STAGE='compile'
mvn -B --no-transfer-progress -f "${REPO_ROOT}/backend-reference/pom.xml" \
  test-compile >"${STATE_DIR}/compile.log" 2>&1 || {
    fail 'test-compile 失败'
  }
info 'test-compile 通过'

STAGE='test'
mvn -B --no-transfer-progress -f "${REPO_ROOT}/backend-reference/pom.xml" \
  "-Dtest=ReferenceMeetingHttpAssemblyIntegrationTest#${TEST_SELECTOR}" \
  test >"${STATE_DIR}/test.log" 2>&1 || {
    fail '健康真网络测试失败'
  }
STAGE='summary'
capture_surefire_counts
[[ "${SUREFIRE_COUNTS}" == '1,0,0,0' ]] || fail 'Surefire 数量不可信'
info 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0'
info '正在精确清理本次专属 PostgreSQL 容器、数据卷和日志目录'
STAGE='cleanup'
