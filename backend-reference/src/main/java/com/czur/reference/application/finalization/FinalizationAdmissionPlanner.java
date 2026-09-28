package com.czur.reference.application.finalization;

import com.czur.reference.application.port.FinalizationInputFact;
import com.czur.reference.application.port.entitlement.UsageReservation;

/**
 * Admission 首次路径使用的确定性最终收口计划端口。
 *
 * <p>该接口存在的目的不是允许运行期任意替换历史计划，而是让事务编排依赖一个
 * 无副作用的纯计算边界，并允许门禁用计数替身证明：完整重放只从持久 checkpoint
 * 恢复，绝不会再次调用 Planner。实现不得读取数据库、当前时钟或外部服务。</p>
 */
@FunctionalInterface
public interface FinalizationAdmissionPlanner {
    DeterministicMockFinalizationPlanner.Plan derive(
            String deploymentScope,
            String subjectScope,
            FinalizationInputFact input,
            FinalizationExecutionFact executionFact,
            UsageReservation lockedReservation);
}
