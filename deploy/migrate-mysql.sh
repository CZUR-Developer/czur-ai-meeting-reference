#!/usr/bin/env bash
set -Eeuo pipefail

# 兼容旧调用名；参考代码当前唯一初始化入口是 deploy/migrate.sh。
# 该别名不包含第二份 SQL，也不改变数据库目标或迁移登记。
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec bash "${SCRIPT_DIR}/migrate.sh" "$@"
