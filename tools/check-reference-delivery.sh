#!/usr/bin/env bash
set -Eeuo pipefail

# 交付前的轻量库存检查。它只验证公开参考仓的入口和敏感文件边界，
# 不启动 Docker、数据库、模型或 Android 设备，也不把静态结果冒充产品验收。
REPO_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_DIR}"

required_files=(
  README.md
  pom.xml
  Dockerfile
  docs/README.md
  docs/partner-integration-guide.md
  backend-core/README.md
  backend-reference/README.md
  android-reference-app/settings.gradle.kts
  android-reference-app/README.md
  web-reference/package.json
  tools/reference-client.env.example
  tools/run-reference-client-backend.sh
  tools/migrate-reference-client.sh
  tools/check-reference-backend.sh
  deploy/migrate.sh
  deploy/healthcheck-db.sh
  deploy/migrations/README.md
  deploy/migrations/V001__initial_schema.sql
)

for file in "${required_files[@]}"; do
  [[ -f "${file}" ]] || { printf '[FAIL] 缺少交付文件: %s\n' "${file}" >&2; exit 1; }
done

# 数据库交付边界：参考方案只交付一份 MySQL 5.7 当前基线；数据库容器和第二份
# SQL 不得进入公开库存，也不能被部署入口或 Compose 误当成本期数据库方案。
mapfile -t migration_files < <(find deploy/migrations -type f -name '*.sql' -print | sort)
[[ "${#migration_files[@]}" -eq 1 && "${migration_files[0]}" == "deploy/migrations/V001__initial_schema.sql" ]] \
  || { printf '[FAIL] 交付必须只有 deploy/migrations/V001__initial_schema.sql\n' >&2; exit 1; }
grep -q '^-- MySQL 5\.7' deploy/migrations/V001__initial_schema.sql \
  || { printf '[FAIL] V001 未声明 MySQL 5.7 基线\n' >&2; exit 1; }
grep -q 'ENGINE=InnoDB' deploy/migrations/V001__initial_schema.sql \
  || { printf '[FAIL] V001 未包含 InnoDB 表定义\n' >&2; exit 1; }
# Compose 只编排应用；数据库容器和 JDBC 地址都不属于本文件交付内容。
if grep -qiE 'jdbc:[a-z]+://|image:.*(mysql|mariadb)' deploy/docker-compose.yml; then
  printf '[FAIL] Compose 不得包含数据库容器或 JDBC 地址\n' >&2
  exit 1
fi
grep -q 'jdbc:mysql://' .env.example \
  || { printf '[FAIL] 默认配置未指向 MySQL\n' >&2; exit 1; }

while IFS= read -r script; do
  bash -n "${script}"
done < <(find tools deploy -type f -name '*.sh' -not -path '*/target/*' -print)

if git ls-files | grep -E '(^|/)(\.env|reference-client\.env|.*\.(p12|jks|keystore|pem|key))$' >/dev/null; then
  printf '[FAIL] Git 索引包含本地配置或证书文件\n' >&2
  exit 1
fi

git diff --check
python3 tools/check-public-boundary.py
printf '[PASS] 参考交付库存、脚本语法、公开边界和 diff 检查通过。\n'
