# Development tools

Contract validators, fixture runners, and acceptance scripts are kept here.
Tools must be deterministic and must not require production credentials.

`check-outbox-migration.py` 检查 Outbox 租约栅栏迁移是否包含字段、非负约束、
幂等 DDL 和过期租约索引，并拒绝在迁移中写入或删除业务数据：

```bash
python3 tools/check-outbox-migration.py
```
