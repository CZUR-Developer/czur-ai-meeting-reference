#!/usr/bin/env bash
set -Eeuo pipefail

# P2-T14-09-10 真实双 JVM 恢复门。脚本只创建本次随机命名且带归属标签的
# PostgreSQL 16 容器和数据卷，并把数据库随机绑定到 IPv4 loopback。
# 禁止连接、停止、挂载或清理任何已有资源，尤其禁止访问保留端口 55432。

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
LOG_LEVEL="${LOG_LEVEL:-INFO}"

declare -A LOG_RANK=([ERROR]=0 [WARN]=1 [INFO]=2 [DEBUG]=3)

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
RESOURCE_PREFIX="czur-t14-worker-cross-jvm-${RUN_ID}"
CONTAINER_NAME="${RESOURCE_PREFIX}-pg"
VOLUME_NAME="${RESOURCE_PREFIX}-data"
OWNERSHIP_LABEL_KEY="com.czur.ai-meeting.t14-worker-cross-jvm.run-id"
CONTAINER_CREATE_ATTEMPTED=0
VOLUME_CREATE_ATTEMPTED=0
CLEANUP_OK=1

is_safe_resource_name() {
  local value="$1"
  [[ "${value}" =~ ^czur-t14-worker-cross-jvm-[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9]+-(pg|data)$ ]]
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
    if ! is_safe_resource_name "${CONTAINER_NAME}"; then
      log ERROR "拒绝清理未通过精确前缀校验的容器名"
      CLEANUP_OK=0
    elif metadata="$(container_metadata 2>/dev/null)"; then
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
      CONTAINER_CREATE_ATTEMPTED=0
    else
      log ERROR "Docker daemon 不可用，无法确认本次容器是否已清理"
      CLEANUP_OK=0
    fi
  fi

  if (( VOLUME_CREATE_ATTEMPTED == 1 )); then
    if ! is_safe_resource_name "${VOLUME_NAME}"; then
      log ERROR "拒绝清理未通过精确前缀校验的数据卷名"
      CLEANUP_OK=0
    elif metadata="$(volume_metadata 2>/dev/null)"; then
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
  fi
  set -e
}

on_signal() {
  local signal="$1"
  log WARN "收到 ${signal}，开始精确清理本次资源"
  exit 130
}

trap cleanup EXIT
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM
trap 'on_signal HUP' HUP

is_safe_resource_name "${CONTAINER_NAME}" || fail "容器名安全校验失败"
is_safe_resource_name "${VOLUME_NAME}" || fail "数据卷名安全校验失败"

for command in docker mvn; do
  command -v "${command}" >/dev/null 2>&1 || fail "缺少命令：${command}"
done
docker info >/dev/null 2>&1 || fail "Docker daemon 不可用"

if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  fail "本次容器名发生碰撞，请重新运行"
fi
if docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1; then
  fail "本次数据卷名发生碰撞，请重新运行"
fi

DB_NAME="t14_worker_cross_jvm"
DB_USER="t14_worker_cross_jvm_user"
DB_PASSWORD="${RESOURCE_PREFIX}-${RANDOM}"

log INFO "本次专属资源 container=${CONTAINER_NAME} volume=${VOLUME_NAME}"
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

BINDING="$(docker port "${CONTAINER_NAME}" 5432/tcp)"
[[ "${BINDING}" =~ ^127\.0\.0\.1:[0-9]+$ ]] \
  || fail "数据库端口必须随机绑定到 IPv4 loopback"
HOST_PORT="${BINDING##*:}"
[[ "${HOST_PORT}" != "55432" ]] \
  || fail "随机端口命中保留端口 55432，拒绝连接并请重新运行"

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
log INFO "数据库镜像和随机 loopback 绑定已确认 image=postgres:16 os=debian"

export REFERENCE_TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:${HOST_PORT}/${DB_NAME}"
export REFERENCE_TEST_DATABASE_USER="${DB_USER}"
export REFERENCE_TEST_DATABASE_PASSWORD="${DB_PASSWORD}"
unset T14_WORKER_CROSS_JVM_DATABASE_URL
unset T14_WORKER_CROSS_JVM_ARTIFACT_ROOT
unset T14_WORKER_CROSS_JVM_CONTROL_FILE

log INFO "启动 P2-T14-09-10 五窗口真实双 JVM 门禁（连接信息已隐藏）"
mvn -B --no-transfer-progress \
  -f "${REPO_ROOT}/backend-reference/pom.xml" \
  -Dtest=MockFinalizationWorkerCrossJvmIntegrationTest test

trap - EXIT
cleanup
(( CLEANUP_OK == 1 )) || fail "本次资源清理失败，拒绝报告门禁通过"
if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  fail "本次容器清理后仍可见"
fi
if docker volume inspect "${VOLUME_NAME}" >/dev/null 2>&1; then
  fail "本次数据卷清理后仍可见"
fi

# 删除带 publish 规则的专属容器后，Docker 应同步释放随机宿主端口。这里不扫描、
# 不关闭任何其它监听者，只反复探测本次已记录的精确 loopback 端口；仍能建立连接
# 表示本次门禁没有给出足够的端口回收证明，必须关闭式失败。
port_released=0
for (( attempt=1; attempt<=30; attempt++ )); do
  if ! { exec 3<>"/dev/tcp/127.0.0.1/${HOST_PORT}"; } 2>/dev/null; then
    port_released=1
    break
  fi
  exec 3>&-
  exec 3<&-
  sleep 0.1
done
(( port_released == 1 )) || fail "本次随机 loopback 端口清理后仍可连接"

log INFO "P2-T14-09-10 PostgreSQL 16 双 JVM Worker 恢复门禁通过，容器、卷和端口已精确清理"
