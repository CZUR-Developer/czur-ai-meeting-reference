#!/usr/bin/env python3
"""静态检查 Outbox 租约栅栏迁移的字段、约束和幂等边界。

该脚本不连接数据库，不能替代 PostgreSQL 运行时迁移；它用于在没有
Docker/psql 的开发环境中尽早发现迁移文件被误删、改成不可重复执行，或
意外包含业务数据写入的问题。
"""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "deploy" / "migrations" / "V002__outbox_lease_fencing.sql"


def fail(message: str) -> int:
    print(f"OUTBOX_MIGRATION_CHECK_FAILED: {message}")
    return 1


def main() -> int:
    if not MIGRATION.is_file():
        return fail(f"迁移文件不存在：{MIGRATION}")
    sql = MIGRATION.read_text(encoding="utf-8")
    normalized = re.sub(r"\s+", " ", sql).lower()

    required_fragments = {
        "事务开始": "begin;",
        "事务提交": "commit;",
        "lease_token 字段": "add column if not exists lease_token text",
        "lease_version 字段": "add column if not exists lease_version bigint not null default 0",
        "lease_version 非负约束": "check (lease_version >= 0)",
        "lease_token 非空白约束": "length(btrim(lease_token)) > 0",
        "过期租约索引": "create index if not exists outbox_leased_expiry_idx",
        "缺失表保护": "to_regclass('outbox_event')",
        "空版本保护": "where lease_version is null",
        "版本 NOT NULL": "alter column lease_version set not null",
    }
    for label, fragment in required_fragments.items():
        if fragment not in normalized:
            return fail(f"缺少{label}：{fragment}")

    # 两个 ALTER 均使用 IF NOT EXISTS，重复执行时不会重复创建字段。
    if normalized.count("add column if not exists") < 2:
        return fail("字段增加必须对 lease_token 和 lease_version 都使用 IF NOT EXISTS")

    # 迁移只允许修改 schema；不应写入或删除会议、凭据、音频等业务数据。
    forbidden = re.search(r"\b(insert|update|delete|truncate|drop)\b", normalized)
    if forbidden:
        return fail(f"发现禁止的业务数据写入/删除关键字：{forbidden.group(1)}")

    # 约束和注释都应明确指向 outbox_event，避免误加到其他表。
    if normalized.count("outbox_event") < 6:
        return fail("outbox_event 引用过少，疑似迁移目标不完整")

    print("OUTBOX_MIGRATION_CHECK_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
