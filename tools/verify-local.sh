#!/usr/bin/env bash
set -Eeuo pipefail

# 日常开发验证直接使用开发机上的 Java、Maven、Python、Node.js 和 npm。
# 参考客户端的真实数据库集成测试只连接调用方显式提供的隔离 MySQL 5.7；
# 本脚本不启动容器、创建数据库或自动执行迁移。若需要本地临时数据库，
# 请由操作者单独运行 mysql:5.7，并在环境中提供测试库连接。
REPO_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_DIR}"

./gradlew checkStructure
mvn -B -f pom.xml test
python3 tools/contract-fixtures/validate.py
python3 -m unittest discover -s tools/android-device -p 'test_*.py'
npm --prefix web-reference run check
npm --prefix web-reference run build

if [[ -z "${REFERENCE_TEST_MYSQL_DATABASE_URL:-}" ]]; then
  printf '[INFO] 未设置 REFERENCE_TEST_MYSQL_DATABASE_URL；MySQL 5.7 集成测试按测试类约定跳过。\n'
else
  printf '[INFO] 已提供隔离 MySQL 连接；参考客户端 MySQL 集成测试已纳入上述 Maven 测试。\n'
fi
if [[ -n "${REFERENCE_TEST_DATABASE_URL:-}" ]]; then
  printf '[INFO] 检测到旧 PostgreSQL 兼容测试变量；这些测试只适用于显式准备的旧 PG 夹具，不是本期参考默认路径。\n'
fi
printf '[INFO] 本机参考代码基础验证通过。\n'
