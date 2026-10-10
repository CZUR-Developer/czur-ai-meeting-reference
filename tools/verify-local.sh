#!/usr/bin/env bash
set -Eeuo pipefail

# 日常开发验证直接使用开发机上的 Java、Maven、Python 和 Node.js。
# PostgreSQL 集成测试只连接调用方显式提供的隔离测试库；本脚本不启动容器，
# 也不会自行选择或迁移任何数据库。
REPO_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_DIR}"

./gradlew checkStructure
mvn -B -f pom.xml test
python3 tools/contract-fixtures/validate.py
npm --prefix web-reference run check
npm --prefix web-reference run build

if [[ -z "${REFERENCE_TEST_DATABASE_URL:-}" ]]; then
  printf '[INFO] 未设置 REFERENCE_TEST_DATABASE_URL；PostgreSQL 集成测试按测试类约定跳过。\n'
fi
printf '[INFO] 本机基础验证通过。\n'
