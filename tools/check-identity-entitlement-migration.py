#!/usr/bin/env python3
"""静态检查 V003 身份、权益迁移的关键数据安全边界。

本脚本不连接 PostgreSQL，不能替代真实旧库升级测试。它负责在普通开发环境
中尽早发现历史数据删除/合并、四元组幂等范围丢失、严格终态约束被删，或迁移
失去重复执行能力等结构性回退。
"""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "deploy" / "migrations" / "V003__identity_entitlement.sql"
REPOSITORY = (
    ROOT
    / "backend-reference"
    / "src"
    / "main"
    / "java"
    / "com"
    / "czur"
    / "reference"
    / "application"
    / "persistence"
    / "jdbc"
    / "JdbcMeetingRepository.java"
)
OWNERSHIP = (
    ROOT
    / "backend-reference"
    / "src"
    / "main"
    / "java"
    / "com"
    / "czur"
    / "reference"
    / "domain"
    / "meeting"
    / "MeetingOwnership.java"
)


def fail(message: str) -> int:
    print(f"IDENTITY_ENTITLEMENT_MIGRATION_CHECK_FAILED: {message}")
    return 1


def main() -> int:
    if not MIGRATION.is_file():
        return fail(f"迁移文件不存在：{MIGRATION}")
    if not REPOSITORY.is_file():
        return fail(f"JDBC 兼容适配器不存在：{REPOSITORY}")
    if not OWNERSHIP.is_file():
        return fail(f"会议归属保留值不存在：{OWNERSHIP}")

    sql = MIGRATION.read_text(encoding="utf-8")
    normalized = re.sub(r"\s+", " ", sql).lower()
    java = REPOSITORY.read_text(encoding="utf-8")
    ownership_java = OWNERSHIP.read_text(encoding="utf-8")

    required_fragments = {
        "事务开始": "begin;",
        "事务提交": "commit;",
        "迁移前未知状态校验": "status not in ('reserved', 'committed', 'cancelled')",
        "历史未归属主体": "legacy_unowned",
        "历史格式标记": "legacy_v1",
        "新格式标记": "record_format = 'v3'",
        "会前预期会议": "expected_meeting_id",
        "可空旧 meeting_id": "alter column meeting_id drop not null",
        "绑定会议延迟外键": "deferrable initially deferred",
        "按语义识别旧外键": "from pg_constraint",
        "旧外键列号匹配": "conkey = array[meeting_column]::smallint[]",
        "预占严格终态约束": "usage_reservation_v3_ck",
        "历史终态确定性约束": "cancellation_reason_code = 'legacy_migration'",
        "最终用量": "actual_amount",
        "提交时间": "committed_at",
        "取消时间": "cancelled_at",
        "取消原因": "cancellation_reason_code",
        "过期未绑定索引": "usage_reservation_expired_unbound_idx",
        "权益操作表": "create table if not exists usage_operation",
        "权益与预占复合范围外键": "foreign key (deployment_scope, subject_scope, reservation_id)",
        "权益结果 JSON": "result_body jsonb not null",
        "会议 Grant": "add column if not exists grant_id text",
        "会议主 Reservation": "add column if not exists usage_reservation_id text",
    }
    for label, fragment in required_fragments.items():
        if fragment not in normalized:
            return fail(f"缺少{label}：{fragment}")

    primary_key = re.search(
        r"primary key\s*\(\s*deployment_scope\s*,\s*subject_scope\s*,"
        r"\s*operation_scope\s*,\s*idempotency_key\s*\)",
        normalized,
    )
    if primary_key is None:
        return fail("usage_operation 必须使用部署、主体、操作、幂等键四元组主键")

    # V003 可以回填历史行、解除旧前置约束，但不能删除、合并或重建业务表。
    forbidden = re.search(
        r"\b(delete\s+from|truncate|drop\s+table|drop\s+column|rename\s+column)\b",
        normalized,
    )
    if forbidden:
        return fail(f"发现会破坏历史事实的 SQL：{forbidden.group(1)}")
    if "group by" in normalized:
        return fail("迁移禁止按会议聚合历史 usage；每条历史记录必须独立保留")

    if normalized.count("add column if not exists") < 20:
        return fail("V003 新增字段必须使用 IF NOT EXISTS 以支持迁移登记失败后重跑")
    if "create index if not exists" not in normalized:
        return fail("V003 索引必须可重复创建")

    validation_at = normalized.index("status not in ('reserved', 'committed', 'cancelled')")
    first_schema_change = normalized.index("alter table meeting add column")
    if validation_at > first_schema_change:
        return fail("未知历史状态必须在首次 schema/data 变更前 fail-fast")

    if (
        'public static final string legacy_unowned_subject_scope = "legacy_unowned";'
        not in ownership_java.lower()
    ):
        return fail("LEGACY_UNOWNED 必须由唯一会议归属常量声明")
    if "meetingownership.legacy_unowned_subject_scope" not in java.lower():
        return fail("旧 Java 兼容入口必须写入 LEGACY_UNOWNED，而不是可认领主体")

    print("IDENTITY_ENTITLEMENT_MIGRATION_CHECK_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
