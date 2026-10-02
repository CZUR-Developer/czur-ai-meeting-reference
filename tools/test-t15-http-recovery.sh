#!/usr/bin/env bash
set -Eeuo pipefail

# 04-04 两 JVM 门禁：本次随机 PG16/卷/Artifact 目录，V001 基线只初始化一次。
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$-${RANDOM}"
RESOURCE_PREFIX="czur-t15-http-recovery-${RUN_ID}"
CONTAINER_NAME="${RESOURCE_PREFIX}-pg"
VOLUME_NAME="${RESOURCE_PREFIX}-data"
LABEL_KEY='com.czur.ai-meeting.t15-http-recovery.run-id'
STATE_DIR=''
CREATED_CONTAINER=0
CREATED_VOLUME=0
CONTAINER_ID=''
STAGE='preflight'
SUREFIRE_COUNTS='NA'
A_SUREFIRE_COUNTS='NA'
B_SUREFIRE_COUNTS='NA'
CONTAINER_CLEANUP='NOT_CREATED'
VOLUME_CLEANUP='NOT_CREATED'
STATE_CLEANUP='NOT_CREATED'

fail() { printf '[ERROR] %s\n' "$*" >&2; exit 1; }
info() { printf '[INFO] %s\n' "$*"; }

cleanup() {
  local metadata id label failed=0 docker_available=1
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
        '{{.Id}}|{{index .Config.Labels "com.czur.ai-meeting.t15-http-recovery.run-id"}}' \
        "${CONTAINER_NAME}" 2>/dev/null || true)"
      if [[ -n "${metadata}" ]]; then
        id="${metadata%%|*}"
        label="${metadata#*|}"
        if [[ "${id}" =~ ^[0-9a-f]{64}$ && "${label}" == "${RUN_ID}" \
            && ( -z "${CONTAINER_ID}" || "${id}" == "${CONTAINER_ID}" ) ]]; then
          if docker rm -f -- "${id}" >/dev/null 2>&1 \
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
        '{{.Name}}|{{index .Labels "com.czur.ai-meeting.t15-http-recovery.run-id"}}' \
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
    if [[ "${STATE_DIR}" == /tmp/czur-t15-http-recovery-state.* \
        && ! -L "${STATE_DIR}" ]]; then
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
  local file="$1" raw=''
  SUREFIRE_COUNTS='NA'
  if [[ -n "${STATE_DIR}" && -f "${file}" ]]; then
    raw="$(rg -o 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' \
      "${file}" 2>/dev/null | tail -1 || true)"
  fi
  if [[ "${raw}" =~ ^Tests[[:space:]]run:[[:space:]]([0-9]+),[[:space:]]Failures:[[:space:]]([0-9]+),[[:space:]]Errors:[[:space:]]([0-9]+),[[:space:]]Skipped:[[:space:]]([0-9]+)$ ]]; then
    SUREFIRE_COUNTS="${BASH_REMATCH[1]},${BASH_REMATCH[2]},${BASH_REMATCH[3]},${BASH_REMATCH[4]}"
  fi
}

on_exit() {
  local result=$? cleanup_failed=0 diagnostic_dir=''
  trap - EXIT
  set +e
  if [[ -n "${STATE_DIR}" ]]; then
    capture_surefire_counts "${STATE_DIR}/phase-a.log"
    A_SUREFIRE_COUNTS="${SUREFIRE_COUNTS}"
    capture_surefire_counts "${STATE_DIR}/phase-b.log"
    B_SUREFIRE_COUNTS="${SUREFIRE_COUNTS}"
  fi
  cleanup || cleanup_failed=1
  if (( result == 0 && cleanup_failed == 0 )); then
    info '隔离 PostgreSQL 容器、卷及私有 Artifact/状态目录已清理'
    exit 0
  fi
  if (( result == 0 )); then result=1; fi
  diagnostic_dir="$(mktemp -d /tmp/czur-t15-http-recovery-diagnostic.XXXXXX)"
  if [[ -n "${diagnostic_dir}" ]]; then
    chmod 700 -- "${diagnostic_dir}"
    (umask 077; printf 'stage=%s\nexit_code=%s\nphase_a_surefire=%s\nphase_b_surefire=%s\ncontainer_cleanup=%s\nvolume_cleanup=%s\nstate_cleanup=%s\n' \
      "${STAGE}" "${result}" "${A_SUREFIRE_COUNTS}" "${B_SUREFIRE_COUNTS}" "${CONTAINER_CLEANUP}" \
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
command -v rg >/dev/null || fail '缺少 rg'
docker info >/dev/null || fail 'Docker daemon 不可用'
[[ "${CONTAINER_NAME}" =~ ^czur-t15-http-recovery-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-pg$ ]] \
  || fail '容器名不安全'
[[ "${VOLUME_NAME}" =~ ^czur-t15-http-recovery-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-data$ ]] \
  || fail '数据卷名不安全'
docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1 && fail '容器名碰撞'
docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1 && fail '卷名碰撞'

STAGE='setup'
STATE_DIR="$(mktemp -d /tmp/czur-t15-http-recovery-state.XXXXXX)"
chmod 700 -- "${STATE_DIR}"
mkdir -m 700 -- "${STATE_DIR}/artifacts"
export T15_HTTP_RECOVERY_ARTIFACT_ROOT="${STATE_DIR}/artifacts"
export T15_HTTP_RECOVERY_STATE_FILE="${STATE_DIR}/recovery-state.bin"
export T15_HTTP_RECOVERY_STAGE='A'
export T15_HTTP_RECOVERY_RUN_ID="${RUN_ID}"
DB_NAME='t15_http_recovery'
DB_USER='t15_http_recovery_user'
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
CONTAINER_ID="$(docker container inspect --format '{{.Id}}' "${CONTAINER_NAME}")"
[[ "${CONTAINER_ID}" =~ ^[0-9a-f]{64}$ ]] || fail '容器 ID 格式异常'
[[ "$(docker container inspect --format \
  '{{index .Config.Labels "com.czur.ai-meeting.t15-http-recovery.run-id"}}' \
  "${CONTAINER_NAME}")" == "${RUN_ID}" ]] || fail '容器归属不符'
[[ "$(docker volume inspect --format \
  '{{index .Labels "com.czur.ai-meeting.t15-http-recovery.run-id"}}' \
  "${VOLUME_NAME}")" == "${RUN_ID}" ]] || fail '数据卷归属不符'
mounted_volume="$(docker container inspect --format \
  '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' \
  "${CONTAINER_ID}")"
[[ "${mounted_volume}" == "${VOLUME_NAME}" ]] || fail 'PostgreSQL 未使用本次专属卷'
artifact_identity="$(stat -c '%d:%i' -- "${T15_HTTP_RECOVERY_ARTIFACT_ROOT}")"

wait_ready() {
  local attempt
  for (( attempt=1; attempt<=90; attempt++ )); do
    docker exec "${CONTAINER_ID}" pg_isready --quiet --username "${DB_USER}" \
      --dbname "${DB_NAME}" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
wait_ready || fail 'PostgreSQL 未就绪'
[[ "$(docker exec "${CONTAINER_ID}" postgres --version)" == *' 16.'* ]] \
  || fail '数据库不是 PostgreSQL 16'
binding="$(docker port "${CONTAINER_ID}" 5432/tcp)"
[[ "${binding}" =~ ^127\.0\.0\.1:[0-9]+$ && "${binding}" != '127.0.0.1:55432' ]] \
  || fail '数据库未随机绑定独占 loopback'
for (( attempt=1; attempt<=90; attempt++ )); do
  if { exec 3<>"/dev/tcp/127.0.0.1/${binding##*:}"; } 2>/dev/null; then
    exec 3>&-
    break
  fi
  sleep 1
done
(( attempt <= 90 )) || fail '宿主数据库 loopback 端口未就绪'
export REFERENCE_TEST_DATABASE_URL="jdbc:postgresql://${binding}/${DB_NAME}"
export REFERENCE_TEST_DATABASE_USER="${DB_USER}"
export REFERENCE_TEST_DATABASE_PASSWORD="${DB_PASSWORD}"

STAGE='migrate'
docker exec --env "DATABASE_URL=postgresql://127.0.0.1:5432/${DB_NAME}" \
  --env "DATABASE_USER=${DB_USER}" --env "DATABASE_PASSWORD=${DB_PASSWORD}" \
  "${CONTAINER_ID}" /opt/czur-deploy/migrate.sh >"${STATE_DIR}/migrate.log" 2>&1 \
  || fail 'V001 基线初始化失败'
migration_count="$(docker exec --env "PGPASSWORD=${DB_PASSWORD}" "${CONTAINER_ID}" \
  psql --username "${DB_USER}" --dbname "${DB_NAME}" --tuples-only --no-align \
  --command 'SELECT count(*) FROM schema_migrations;' | tr -d '[:space:]')"
[[ "${migration_count}" == 1 ]] || fail '迁移登记数量不是唯一 V001'

STAGE='phase-a'
mvn -B --no-transfer-progress -f "${REPO_ROOT}/backend-reference/pom.xml" \
  '-Dtest=T15HttpRecoveryIntegrationTest#phaseAPublishesPersistentArtifactOverRealHttp' \
  test >"${STATE_DIR}/phase-a.log" 2>&1 || fail '阶段 A Maven/JVM 失败'
capture_surefire_counts "${STATE_DIR}/phase-a.log"
[[ "${SUREFIRE_COUNTS}" == '1,0,0,0' ]] || fail '阶段 A Surefire 数量不可信'
A_SUREFIRE_COUNTS="${SUREFIRE_COUNTS}"
[[ -f "${T15_HTTP_RECOVERY_STATE_FILE}" && ! -L "${T15_HTTP_RECOVERY_STATE_FILE}" ]] \
  || fail '阶段 A 未写出普通状态文件'
[[ "$(stat -c '%a' "${T15_HTTP_RECOVERY_STATE_FILE}")" == 600 ]] \
  || fail '跨进程状态文件权限不是 600'
if rg -a -q 'Bearer|t15-recovery-token|wss://|sessionToken' \
    "${T15_HTTP_RECOVERY_STATE_FILE}"; then
  fail '跨进程状态文件含禁止内容'
fi
info '阶段 A：Tests run: 1, Failures: 0, Errors: 0, Skipped: 0'
STAGE='database-restart'
docker stop --time 30 "${CONTAINER_ID}" >/dev/null || fail 'PostgreSQL 停止失败'
docker start "${CONTAINER_ID}" >/dev/null || fail 'PostgreSQL 重启失败'
[[ "$(docker container inspect --format '{{.Id}}' "${CONTAINER_NAME}")" == "${CONTAINER_ID}" ]] \
  || fail '重启后容器 ID 变化'
[[ "$(docker container inspect --format \
  '{{index .Config.Labels "com.czur.ai-meeting.t15-http-recovery.run-id"}}' \
  "${CONTAINER_ID}")" == "${RUN_ID}" ]] || fail '重启后容器归属变化'
[[ "$(docker container inspect --format \
  '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' \
  "${CONTAINER_ID}")" == "${VOLUME_NAME}" ]] || fail '重启后 PostgreSQL 卷变化'
[[ "$(stat -c '%d:%i' -- "${T15_HTTP_RECOVERY_ARTIFACT_ROOT}")" == "${artifact_identity}" ]] \
  || fail '重启后 Artifact 目录变化'
wait_ready || fail 'PostgreSQL 重启后未就绪'
binding="$(docker port "${CONTAINER_ID}" 5432/tcp)"
[[ "${binding}" =~ ^127\.0\.0\.1:[0-9]+$ && "${binding}" != '127.0.0.1:55432' ]] \
  || fail '重启后数据库未随机绑定独占 loopback'
for (( attempt=1; attempt<=90; attempt++ )); do
  if { exec 3<>"/dev/tcp/127.0.0.1/${binding##*:}"; } 2>/dev/null; then
    exec 3>&-
    break
  fi
  sleep 1
done
(( attempt <= 90 )) || fail '重启后宿主数据库 loopback 端口未就绪'
export REFERENCE_TEST_DATABASE_URL="jdbc:postgresql://${binding}/${DB_NAME}"
[[ "$(docker exec --env "PGPASSWORD=${DB_PASSWORD}" "${CONTAINER_ID}" \
  psql --username "${DB_USER}" --dbname "${DB_NAME}" --tuples-only --no-align \
  --command 'SELECT count(*) FROM schema_migrations;' | tr -d '[:space:]')" == 1 ]] \
  || fail '重启后迁移登记不完整'

STAGE='phase-b'
export T15_HTTP_RECOVERY_STAGE='B'
mvn -B --no-transfer-progress -f "${REPO_ROOT}/backend-reference/pom.xml" \
  '-Dtest=T15HttpRecoveryIntegrationTest#phaseBReopensArtifactAndReplaysAfterDatabaseRestart' \
  test >"${STATE_DIR}/phase-b.log" 2>&1 || fail '阶段 B Maven/JVM 失败'
capture_surefire_counts "${STATE_DIR}/phase-b.log"
[[ "${SUREFIRE_COUNTS}" == '1,0,0,0' ]] || fail '阶段 B Surefire 数量不可信'
B_SUREFIRE_COUNTS="${SUREFIRE_COUNTS}"
info '阶段 B：Tests run: 1, Failures: 0, Errors: 0, Skipped: 0'
info 'A→数据库重启→B 持久 Artifact 读回与事实恢复门禁通过，开始清理本次专属资源'
STAGE='cleanup'
