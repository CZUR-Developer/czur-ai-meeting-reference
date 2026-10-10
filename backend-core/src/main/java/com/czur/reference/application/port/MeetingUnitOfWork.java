package com.czur.reference.application.port;

import java.util.Objects;
import java.util.function.Function;

/**
 * 会议命令的事务边界。
 *
 * <p>一个工作单元把会议快照、幂等记录和 Outbox 写入绑定到同一份事务
 * 上下文。应用层只能通过 {@link Transaction} 访问这三个端口；它不需要
 * 知道连接池或数据库实现。回调正常返回时由实现提交，抛出异常（包括
 * {@link Error}）时由实现回滚。回调中不得调用模型、存储供应商、邮件或
 * 消息队列等外部副作用；这些动作应由提交后的 Outbox Worker 处理。</p>
 */
public interface MeetingUnitOfWork {

    /**
     * 在一个可提交或可回滚的事务中执行工作。
     *
     * @param work 只执行领域和持久化端口操作的事务回调
     * @param <T> 回调返回值类型
     * @return 提交成功后的回调结果
     */
    <T> T execute(Function<Transaction, T> work);

    /**
     * 当前事务内的端口视图。三个端口由实现绑定到同一个事务资源；调用方
     * 不应缓存它们到回调之外，也不应在回调中自行提交、回滚或关闭资源。
     */
    interface Transaction {
        MeetingRepository meetings();

        IdempotencyStore idempotency();

        OutboxStore outbox();
    }

    /** 统一校验事务回调，供实现复用，避免空回调产生半开事务。 */
    static <T> Function<Transaction, T> requireWork(Function<Transaction, T> work) {
        return Objects.requireNonNull(work, "work");
    }
}
