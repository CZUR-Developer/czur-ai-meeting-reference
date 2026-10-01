#!/usr/bin/env bash
set -Eeuo pipefail

# P2-T15-12-06 专用门禁。只创建本次随机命名的容器、卷和状态目录；
# 清理前核对精确名称与归属标签，不触碰已有开发数据库。
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$-${RANDOM}"
RESOURCE_PREFIX="czur-t15-create-${RUN_ID}"
CONTAINER_NAME="${RESOURCE_PREFIX}-pg"
VOLUME_NAME="${RESOURCE_PREFIX}-data"
LABEL_KEY="com.czur.ai-meeting.t15-create.run-id"
STATE_DIR=""
CREATED_CONTAINER=0
CREATED_VOLUME=0

fail() { printf '[ERROR] %s\n' "$*" >&2; exit 1; }
info() { printf '[INFO] %s\n' "$*"; }

cleanup() {
  local meta id label
  if (( CREATED_CONTAINER )); then
    meta="$(docker container inspect --format "{{.Id}}|{{index .Config.Labels \"${LABEL_KEY}\"}}" "${CONTAINER_NAME}" 2>/dev/null || true)"
    if [[ -n "${meta}" ]]; then
      id="${meta%%|*}"; label="${meta#*|}"
      if [[ "${id}" =~ ^[0-9a-f]{64}$ && "${label}" == "${RUN_ID}" ]]; then
        docker rm -f -- "${id}" >/dev/null || return 1
      else
        printf '[ERROR] 本次容器归属不符，拒绝删除\n' >&2; return 1
      fi
    fi
  fi
  if (( CREATED_VOLUME )); then
    meta="$(docker volume inspect --format "{{.Name}}|{{index .Labels \"${LABEL_KEY}\"}}" "${VOLUME_NAME}" 2>/dev/null || true)"
    if [[ -n "${meta}" ]]; then
      label="${meta#*|}"
      if [[ "${meta%%|*}" == "${VOLUME_NAME}" && "${label}" == "${RUN_ID}" ]]; then
        docker volume rm -- "${VOLUME_NAME}" >/dev/null || return 1
      else
        printf '[ERROR] 本次数据卷归属不符，拒绝删除\n' >&2; return 1
      fi
    fi
  fi
  if [[ -n "${STATE_DIR}" && "${STATE_DIR}" == /tmp/czur-t15-create-state.* ]]; then
    rm -rf -- "${STATE_DIR}"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

command -v docker >/dev/null || fail '缺少 docker'
command -v mvn >/dev/null || fail '缺少 mvn'
docker info >/dev/null || fail 'Docker daemon 不可用'
[[ "${CONTAINER_NAME}" =~ ^czur-t15-create-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-pg$ ]] || fail '容器名不安全'
[[ "${VOLUME_NAME}" =~ ^czur-t15-create-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-data$ ]] || fail '数据卷名不安全'
docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1 && fail '容器名发生碰撞'
docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1 && fail '数据卷名发生碰撞'

STATE_DIR="$(mktemp -d /tmp/czur-t15-create-state.XXXXXX)"
chmod 700 -- "${STATE_DIR}"
DB_NAME='t15_create_recovery'
DB_USER='t15_create_user'
DB_PASSWORD="${RESOURCE_PREFIX}-${RANDOM}"
info "本次专属资源 container=${CONTAINER_NAME} volume=${VOLUME_NAME}"
CREATED_VOLUME=1
docker volume create --label "${LABEL_KEY}=${RUN_ID}" -- "${VOLUME_NAME}" >/dev/null
CREATED_CONTAINER=1
docker run --detach --name "${CONTAINER_NAME}" \
  --label "${LABEL_KEY}=${RUN_ID}" \
  --env "POSTGRES_DB=${DB_NAME}" --env "POSTGRES_USER=${DB_USER}" \
  --env "POSTGRES_PASSWORD=${DB_PASSWORD}" \
  --mount "type=volume,source=${VOLUME_NAME},target=/var/lib/postgresql/data" \
  --mount "type=bind,source=${REPO_ROOT}/deploy,target=/opt/czur-deploy,readonly" \
  --publish '127.0.0.1::5432' postgres:16 >/dev/null

wait_ready() {
  local attempt
  for (( attempt=1; attempt<=90; attempt++ )); do
    docker exec "${CONTAINER_NAME}" pg_isready --quiet --username "${DB_USER}" --dbname "${DB_NAME}" && return 0
    sleep 1
  done
  return 1
}
refresh_url() {
  local binding port attempt
  binding="$(docker port "${CONTAINER_NAME}" 5432/tcp)"
  [[ "${binding}" =~ ^127\.0\.0\.1:[0-9]+$ ]] || fail '数据库未随机绑定 loopback'
  [[ "${binding}" != '127.0.0.1:55432' ]] || fail '禁止固定共享端口'
  port="${binding##*:}"
  for (( attempt=1; attempt<=90; attempt++ )); do
    if { exec 3<>"/dev/tcp/127.0.0.1/${port}"; } 2>/dev/null; then
      exec 3>&-
      break
    fi
    sleep 1
  done
  (( attempt <= 90 )) || fail '宿主数据库端口未就绪'
  export REFERENCE_TEST_DATABASE_URL="jdbc:postgresql://${binding}/${DB_NAME}"
}
wait_ready || fail 'PostgreSQL 未就绪'
[[ "$(docker exec "${CONTAINER_NAME}" postgres --version)" == *' 16.'* ]] || fail '数据库不是 PostgreSQL 16'
refresh_url
docker exec --env "DATABASE_URL=postgresql://127.0.0.1:5432/${DB_NAME}" \
  --env "DATABASE_USER=${DB_USER}" --env "DATABASE_PASSWORD=${DB_PASSWORD}" \
  "${CONTAINER_NAME}" /opt/czur-deploy/migrate.sh
export REFERENCE_TEST_DATABASE_USER="${DB_USER}"
export REFERENCE_TEST_DATABASE_PASSWORD="${DB_PASSWORD}"
export T15_CREATE_GATE_RUN_ID="${RUN_ID}"
export T15_CREATE_RESTART_STATE_FILE="${STATE_DIR}/create-state.bin"

run_maven() {
  local selector="$1" output="$2" tests failures errors skipped
  mvn -B --no-transfer-progress -f "${REPO_ROOT}/backend-reference/pom.xml" \
    -DskipTests=false -Dmaven.test.skip=false \
    "-Dtest=T15CreateRecoveryIntegrationTest#${selector}" test >"${output}" 2>&1 || {
      tail -n 100 "${output}" >&2; fail "Maven 阶段失败 selector=${selector}"
    }
  tests="$(sed -nE 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+).*/\1/p' "${output}" | tail -1)"
  failures="$(sed -nE 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+).*/\2/p' "${output}" | tail -1)"
  errors="$(sed -nE 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+).*/\3/p' "${output}" | tail -1)"
  skipped="$(sed -nE 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+).*/\4/p' "${output}" | tail -1)"
  [[ "${tests}" =~ ^[1-9][0-9]*$ && "${failures}" == 0 && "${errors}" == 0 && "${skipped}" == 0 ]] \
    || fail "Surefire 数量不可信 selector=${selector} tests=${tests} failures=${failures} errors=${errors} skipped=${skipped}"
  info "${selector}: tests=${tests} failures=0 errors=0 skipped=0"
}

run_maven twoJvmsSameDigestReplayWinnerAndDifferentDigestConflicts "${STATE_DIR}/concurrency.log"
run_maven commitBeforeAndAfterConfirmationLossRemainUnknownAndRecoverSafely "${STATE_DIR}/commit.log"
run_maven prepareBeforeRestart "${STATE_DIR}/prepare.log"
[[ "$(stat -c '%a' "${T15_CREATE_RESTART_STATE_FILE}")" == 600 ]] || fail '状态文件权限不是 600'
docker stop --time 30 "${CONTAINER_NAME}" >/dev/null
docker start "${CONTAINER_NAME}" >/dev/null
wait_ready || fail 'PostgreSQL 重启后未就绪'
refresh_url
run_maven verifyAfterRestart "${STATE_DIR}/verify.log"
info 'P2-T15-12-06 门禁通过，正在精确清理本次专属资源'
cleanup
CREATED_CONTAINER=0
CREATED_VOLUME=0
STATE_DIR=''
trap - EXIT INT TERM HUP
info '本次容器、数据卷与状态目录已清理'
