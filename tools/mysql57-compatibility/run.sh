#!/usr/bin/env bash
set -Eeuo pipefail

# 普通 Java 21 编译；输出限定在已 gitignore 的本工具 target/，不修改后端 POM。
PROBE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--compile-only" ) ]]; then
  printf '[ERROR] 用法：bash tools/mysql57-compatibility/run.sh [--compile-only]\n' >&2
  exit 2
fi
mkdir -p "${PROBE_DIR}/target"
javac --release 21 -encoding UTF-8 -d "${PROBE_DIR}/target" "${PROBE_DIR}/Mysql57CompatibilityProbe.java"
if [[ "${1:-}" == "--compile-only" ]]; then
  printf '[INFO] MYSQL57_PROBE_COMPILE_OK；未连接数据库。\n'
  exit 0
fi

# 不接收命令行密码、不启用 xtrace；密码仅作为继承环境由 Java 使用。
: "${MYSQL57_TEST_URL:?请显式设置本机 ai_trans_probe_* 隔离库 URL}"
: "${MYSQL57_TEST_USER:?请显式设置隔离库用户}"
if [[ -z "${MYSQL57_TEST_PASSWORD+x}" ]]; then
  printf '[ERROR] 请显式设置 MYSQL57_TEST_PASSWORD（隔离账号无密码时可设为空）。\n' >&2
  exit 2
fi
: "${MYSQL57_JDBC_JAR:?请显式设置本机 MySQL Connector/J JAR 路径}"
if [[ ! -r "${MYSQL57_JDBC_JAR}" ]]; then
  printf '[ERROR] MYSQL57_JDBC_JAR 指定的驱动文件不可读。\n' >&2
  exit 2
fi
java -cp "${PROBE_DIR}/target:${MYSQL57_JDBC_JAR}" Mysql57CompatibilityProbe
