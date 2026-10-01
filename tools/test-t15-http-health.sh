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

fail() { printf '[ERROR] %s\n' "$*" >&2; exit 1; }
info() { printf '[INFO] %s\n' "$*"; }

cleanup() {
  local metadata id label
  if (( CREATED_CONTAINER )); then
    metadata="$(docker container inspect --format \
      '{{.Id}}|{{index .Config.Labels "com.czur.ai-meeting.t15-health.run-id"}}' \
      "${CONTAINER_NAME}" 2>/dev/null || true)"
    if [[ -n "${metadata}" ]]; then
      id="${metadata%%|*}"
      label="${metadata#*|}"
      if [[ "${id}" =~ ^[0-9a-f]{64}$ && "${label}" == "${RUN_ID}" \
          && ( -z "${CONTAINER_ID}" || "${id}" == "${CONTAINER_ID}" ) ]]; then
        if [[ "$(docker container inspect --format '{{.State.Paused}}' "${id}")" == true ]]; then
          docker unpause -- "${id}" >/dev/null || return 1
        fi
        docker rm -f -- "${id}" >/dev/null || return 1
      else
        printf '[ERROR] 容器归属不符，拒绝清理\n' >&2; return 1
      fi
    fi
  fi
  if (( CREATED_VOLUME )); then
    metadata="$(docker volume inspect --format \
      '{{.Name}}|{{index .Labels "com.czur.ai-meeting.t15-health.run-id"}}' \
      "${VOLUME_NAME}" 2>/dev/null || true)"
    if [[ -n "${metadata}" ]]; then
      label="${metadata#*|}"
      if [[ "${metadata%%|*}" == "${VOLUME_NAME}" && "${label}" == "${RUN_ID}" ]]; then
        docker volume rm -- "${VOLUME_NAME}" >/dev/null || return 1
      else
        printf '[ERROR] 数据卷归属不符，拒绝清理\n' >&2; return 1
      fi
    fi
  fi
  if [[ -n "${STATE_DIR}" && "${STATE_DIR}" == /tmp/czur-t15-health-state.* ]]; then
    rm -rf -- "${STATE_DIR}"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

command -v docker >/dev/null || fail '缺少 docker'
command -v mvn >/dev/null || fail '缺少 mvn'
command -v python3 >/dev/null || fail '缺少随机端口选择所需的 python3'
docker info >/dev/null || fail 'Docker daemon 不可用'
[[ "${CONTAINER_NAME}" =~ ^czur-t15-health-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-pg$ ]] \
  || fail '容器名不安全'
[[ "${VOLUME_NAME}" =~ ^czur-t15-health-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-data$ ]] \
  || fail '数据卷名不安全'
docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1 && fail '容器名碰撞'
docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1 && fail '卷名碰撞'

STATE_DIR="$(mktemp -d /tmp/czur-t15-health-state.XXXXXX)"
chmod 700 -- "${STATE_DIR}"
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

mvn -B --no-transfer-progress -f "${REPO_ROOT}/backend-reference/pom.xml" \
  test-compile >"${STATE_DIR}/compile.log" 2>&1 || {
    rg 'BUILD FAILURE|ERROR|error:' "${STATE_DIR}/compile.log" >&2 || true
    fail 'test-compile 失败'
  }
info 'test-compile 通过'

mvn -B --no-transfer-progress -f "${REPO_ROOT}/backend-reference/pom.xml" \
  '-Dtest=ReferenceMeetingHttpAssemblyIntegrationTest#internalHealthUsesSameJdbcAssemblyAcrossDatabaseOutageAndRecovery' \
  test >"${STATE_DIR}/test.log" 2>&1 || {
    rg 'Tests run:|BUILD FAILURE|ERROR|error:' "${STATE_DIR}/test.log" >&2 || true
    fail '健康真网络测试失败'
  }
SUMMARY="$(rg 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' \
  "${STATE_DIR}/test.log" | tail -1)"
[[ "${SUMMARY}" == *'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0'* ]] \
  || fail "Surefire 数量不可信: ${SUMMARY}"
info "${SUMMARY}"
info '正在精确清理本次专属 PostgreSQL 容器、数据卷和日志目录'
cleanup
CONTAINER_ID=''
CREATED_CONTAINER=0
CREATED_VOLUME=0
STATE_DIR=''
trap - EXIT INT TERM HUP
docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1 && fail '容器未清理'
docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1 && fail '数据卷未清理'
info '隔离资源已清理'
