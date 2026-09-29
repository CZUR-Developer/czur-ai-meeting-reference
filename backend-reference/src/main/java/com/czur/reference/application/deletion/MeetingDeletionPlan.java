package com.czur.reference.application.deletion;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 删除计划在数据库中的只读投影。
 *
 * <p>{@link ArtifactDeletionPlan} 是受理时冻结的不可变 Definition；本对象额外携带
 * 数据库中可变的计划和逐项审计状态。它不提供任何状态迁移方法，删除受理、Worker 和
 * 物理 Artifact Store 的操作留给后续切片。所有构造输入都必须已经从同一数据库事务
 * 读回并通过 JDBC Store 的完整一致性校验。</p>
 */
public record MeetingDeletionPlan(
        ArtifactDeletionPlan plan,
        PlanStatus status,
        List<Item> items,
        Instant completedAt) {
    public MeetingDeletionPlan {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(items, "items");
        if (items.size() != plan.targets().size()) {
            throw new IllegalArgumentException("deletion plan item count is invalid");
        }
        if (status == PlanStatus.PENDING && completedAt != null
                || status == PlanStatus.COMPLETED && completedAt == null) {
            throw new IllegalArgumentException("deletion plan status is invalid");
        }
        for (int ordinal = 0; ordinal < items.size(); ordinal++) {
            Item item = Objects.requireNonNull(items.get(ordinal), "item");
            if (item.ordinal() != ordinal
                    || !item.target().equals(plan.targets().get(ordinal))) {
                throw new IllegalArgumentException("deletion plan item ordering is invalid");
            }
        }
        items = List.copyOf(items);
    }

    /** 当前计划是否仍等待后续逐项物理删除。 */
    public enum PlanStatus {
        PENDING,
        COMPLETED
    }

    /** 一个冻结目标及其删除审计状态。 */
    public record Item(
            int ordinal,
            ArtifactDeletionTarget target,
            ItemStatus status,
            String storageBinding,
            Instant deletedAt) {
        public Item {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(status, "status");
            if (ordinal < 0 || ordinal >= ArtifactDeletionPlan.MAX_TARGETS) {
                throw new IllegalArgumentException("deletion item ordinal is invalid");
            }
            if (status == ItemStatus.PENDING
                    && (storageBinding != null || deletedAt != null)
                    || status == ItemStatus.DELETED
                    && (storageBinding == null || storageBinding.isBlank() || deletedAt == null)) {
                throw new IllegalArgumentException("deletion item status is invalid");
            }
        }
    }

    /** V012 逐项审计状态；后续切片才允许 PENDING 到 DELETED。 */
    public enum ItemStatus {
        PENDING,
        DELETED
    }

    /** 首次注册时创建的全量 PENDING 审计投影。 */
    public static MeetingDeletionPlan pending(ArtifactDeletionPlan plan) {
        List<Item> items = java.util.stream.IntStream.range(0, plan.targets().size())
                .mapToObj(index -> new Item(index, plan.targets().get(index),
                        ItemStatus.PENDING, null, null))
                .toList();
        return new MeetingDeletionPlan(plan, PlanStatus.PENDING, items, null);
    }

    @Override
    public String toString() {
        return "MeetingDeletionPlan[plan=redacted,status=" + status
                + ",itemCount=" + items.size() + "]";
    }
}
