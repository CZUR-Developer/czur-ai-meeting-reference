#!/usr/bin/env bash
set -Eeuo pipefail

# 本机开发入口：使用 Maven／Java 和外部已初始化的数据库，不启动 Docker。
# 客户端直连模型进程只监听 HTTP；Android 通过 modelSession 取得材料后直连模型。
# 配置默认放仓外：check-public-boundary.py 按工作区扫描，仓库里填好密码的配置会让交付检查失败。
REPO_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${REFERENCE_ENV_FILE:-${HOME}/.config/ai-meeting/reference-client.env}"

if [[ ! -f "${ENV_FILE}" ]]; then
  printf '[ERROR] 未找到配置文件：%s\n' "${ENV_FILE}" >&2
  printf '[INFO] 先复制模板到仓外并填写，或用 REFERENCE_ENV_FILE 指定路径：\n' >&2
  printf '[INFO]   mkdir -p ~/.config/ai-meeting\n' >&2
  printf '[INFO]   cp tools/reference-client.env.example ~/.config/ai-meeting/reference-client.env\n' >&2
  exit 1
fi
command -v java >/dev/null 2>&1 || { printf '[ERROR] 未找到 Java 21\n' >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || { printf '[ERROR] 未找到 Maven\n' >&2; exit 1; }

# 配置文件只作为本机环境注入；脚本不打印内容，也不把它复制进制品。
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

cd "${REPO_DIR}"
if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  mvn -B -f pom.xml -pl backend-reference -am install -DskipTests
  mvn -B -q -f backend-reference/pom.xml dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/runtime-libs
fi

JAR="${REPO_DIR}/backend-reference/target/backend-reference-0.1.0-SNAPSHOT.jar"
LIBS="${REPO_DIR}/backend-reference/target/runtime-libs/*"
[[ -f "${JAR}" ]] || { printf '[ERROR] 缺少后端 JAR，请先完成构建\n' >&2; exit 1; }
exec java -cp "${JAR}:${LIBS}" \
  com.czur.reference.application.bootstrap.ReferenceClientMeetingMain
