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
  deploy/migrations/V001__initial_schema.sql
)

for file in "${required_files[@]}"; do
  [[ -f "${file}" ]] || { printf '[FAIL] 缺少交付文件: %s\n' "${file}" >&2; exit 1; }
done

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
