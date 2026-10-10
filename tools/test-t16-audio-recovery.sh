#!/usr/bin/env bash
set -Eeuo pipefail

# 04B-2c 专属 PG16：同一容器/卷一次真实 stop/start，测试 JVM 前后均重新启动。
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$-${RANDOM}"
RESOURCE="czur-t16-audio-${RUN_ID}"
CONTAINER="${RESOURCE}-pg"
VOLUME="${RESOURCE}-data"
LABEL='com.czur.ai-meeting.t16-audio.run-id'
LOG_DIR=""
OWN_CONTAINER=0
OWN_VOLUME=0

fail() { printf '[ERROR] %s\n' "$*" >&2; exit 1; }
info() { printf '[INFO] %s\n' "$*"; }

cleanup() {
  local metadata id name owner
  if (( OWN_CONTAINER )); then
    metadata="$(docker container inspect --format "{{.Id}}|{{index .Config.Labels \"${LABEL}\"}}" "${CONTAINER}" 2>/dev/null || true)"
    if [[ -n "${metadata}" ]]; then
      id="${metadata%%|*}"; owner="${metadata#*|}"
      [[ "${id}" =~ ^[0-9a-f]{64}$ && "${owner}" == "${RUN_ID}" ]] || fail '拒绝清理非本次容器'
      docker rm -f -- "${id}" >/dev/null
    fi
  fi
  if (( OWN_VOLUME )); then
    metadata="$(docker volume inspect --format "{{.Name}}|{{index .Labels \"${LABEL}\"}}" "${VOLUME}" 2>/dev/null || true)"
    if [[ -n "${metadata}" ]]; then
      name="${metadata%%|*}"; owner="${metadata#*|}"
      [[ "${name}" == "${VOLUME}" && "${owner}" == "${RUN_ID}" ]] || fail '拒绝清理非本次数据卷'
      docker volume rm -- "${VOLUME}" >/dev/null
    fi
  fi
  if [[ -n "${LOG_DIR}" && "${LOG_DIR}" == /tmp/czur-t16-audio.* ]]; then
    rm -r -- "${LOG_DIR}"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

command -v docker >/dev/null || fail '缺少 docker'
command -v mvn >/dev/null || fail '缺少 mvn'
docker info >/dev/null || fail 'Docker daemon 不可用'
[[ "${CONTAINER}" =~ ^czur-t16-audio-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-pg$ ]] || fail '容器名不安全'
docker container inspect "${CONTAINER}" >/dev/null 2>&1 && fail '容器名碰撞'
docker volume inspect "${VOLUME}" >/dev/null 2>&1 && fail '卷名碰撞'
LOG_DIR="$(mktemp -d /tmp/czur-t16-audio.XXXXXX)"
chmod 700 -- "${LOG_DIR}"
DB_NAME='t16_audio_recovery'
DB_USER='t16_audio_user'
DB_PASSWORD="${RESOURCE}-${RANDOM}"

OWN_VOLUME=1
docker volume create --label "${LABEL}=${RUN_ID}" -- "${VOLUME}" >/dev/null
OWN_CONTAINER=1
docker run --detach --name "${CONTAINER}" \
  --label "${LABEL}=${RUN_ID}" \
  --env "POSTGRES_DB=${DB_NAME}" --env "POSTGRES_USER=${DB_USER}" \
  --env "POSTGRES_PASSWORD=${DB_PASSWORD}" \
  --mount "type=volume,source=${VOLUME},target=/var/lib/postgresql/data" \
  --mount "type=bind,source=${REPO_ROOT}/deploy,target=/opt/czur-deploy,readonly" \
  --publish '127.0.0.1::5432' postgres:16 >/dev/null

wait_ready() {
  local attempt
  for (( attempt=1; attempt<=60; attempt++ )); do
    docker exec "${CONTAINER}" pg_isready --quiet --username "${DB_USER}" --dbname "${DB_NAME}" && return 0
    sleep 1
  done
  return 1
}
database_url() {
  local binding
  binding="$(docker port "${CONTAINER}" 5432/tcp)"
  [[ "${binding}" =~ ^127\.0\.0\.1:[0-9]+$ && "${binding}" != '127.0.0.1:55432' ]] \
    || fail '数据库未绑定专属随机 loopback 端口'
  export REFERENCE_TEST_DATABASE_URL="jdbc:postgresql://${binding}/${DB_NAME}"
}
database_identity() {
  docker container inspect --format \
    '{{.Id}}|{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' \
    "${CONTAINER}"
}
postmaster_start() {
  docker exec --env "PGPASSWORD=${DB_PASSWORD}" "${CONTAINER}" \
    psql --username "${DB_USER}" --dbname "${DB_NAME}" --no-align --tuples-only \
    --command 'SELECT pg_postmaster_start_time()'
}
wait_ready || fail 'PG16 未就绪'
[[ "$(docker exec "${CONTAINER}" postgres --version)" == *' 16.'* ]] || fail '数据库不是 PG16'
database_url
BEFORE_IDENTITY="$(database_identity)"
[[ "${BEFORE_IDENTITY}" =~ ^[0-9a-f]{64}\|${VOLUME}$ ]] || fail '容器 ID 或数据卷挂载不符'
BEFORE_POSTMASTER="$(postmaster_start)"
[[ -n "${BEFORE_POSTMASTER}" ]] || fail '无法读取重启前 postmaster 时间'
docker exec --env "DATABASE_URL=postgresql://127.0.0.1:5432/${DB_NAME}" \
  --env "DATABASE_USER=${DB_USER}" --env "DATABASE_PASSWORD=${DB_PASSWORD}" \
  "${CONTAINER}" /opt/czur-deploy/migrate.sh
export REFERENCE_TEST_DATABASE_USER="${DB_USER}"
export REFERENCE_TEST_DATABASE_PASSWORD="${DB_PASSWORD}"
export T16_AUDIO_GATE_RUN_ID="${RUN_ID}"
info "专属 PG16 已就绪 container=${CONTAINER} endpoint=${REFERENCE_TEST_DATABASE_URL}"

run_test() {
  local name="$1" log="${LOG_DIR}/${1}.log" counts
  mvn -B --no-transfer-progress -f "${REPO_ROOT}/pom.xml" -pl backend-reference -am \
    "-Dtest=JdbcAudioInputRecoveryIntegrationTest#${name}" test >"${log}" 2>&1 || {
      tail -n 100 "${log}" >&2; fail "测试失败: ${name}"
    }
  counts="$(sed -nE 's/.*Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+).*/\1 \2 \3 \4/p' "${log}" | tail -1)"
  [[ "${counts}" == '1 0 0 0' ]] || fail "测试数量错误: ${name} ${counts}"
  info "${name}: tests=1 failures=0 errors=0 skipped=0"
}

run_test twoJvmWriterReplacementFencesOldFrameAndCompletion
run_test commitFailureBeforeAndConfirmationLossAfterAreResolvedByReadOnlyFacts
run_test frameInsertThenCursorUpdateFailureRollsBackBothFacts
docker stop --time 30 "${CONTAINER}" >/dev/null
docker start "${CONTAINER}" >/dev/null
wait_ready || fail 'PG16 重启后未就绪'
database_url
AFTER_IDENTITY="$(database_identity)"
AFTER_POSTMASTER="$(postmaster_start)"
[[ "${AFTER_IDENTITY}" == "${BEFORE_IDENTITY}" ]] || fail '重启后容器 ID 或数据卷发生变化'
[[ -n "${AFTER_POSTMASTER}" && "${AFTER_POSTMASTER}" != "${BEFORE_POSTMASTER}" ]] \
  || fail 'postmaster 启动时间未变化'
info 'PG16 重启证明: 容器 ID 与数据卷不变，postmaster 启动时间已变化'
run_test readCommittedFramesAfterPostgresRestart
info '04B-2c 定向门禁通过，正在清理专属容器和卷'
cleanup
OWN_CONTAINER=0
OWN_VOLUME=0
LOG_DIR=''
trap - EXIT INT TERM HUP
info '专属容器、卷及测试日志已精确清理'
