package com.czur.reference.application.port;

/**
 * 生成会议输入被后端接管后的 opaque receipt。
 *
 * <p>生成器只负责提供候选值，不负责持久化和重放。调用方必须在锁定会议事实的
 * 数据库事务中调用它，并先检查会议是否已经存在 receipt：已经接管的会议必须
 * 原样返回数据库中的既有值，不能再次调用生成器。候选值不是登录凭据，但仍不
 * 得写入日志、异常或对象的默认 {@code toString()}。</p>
 */
@FunctionalInterface
public interface FinalizationReceiptGenerator {
    /**
     * 生成一个新的候选 receipt。
     *
     * @return 非空、非空白的 opaque receipt；唯一性由实现和数据库共同保证
     */
    String newReceipt();
}
