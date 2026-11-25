package com.catgal.server.service;

import java.util.Map;
import java.util.function.Supplier;

/** 旧写入排空、新事务激活及切换核对。ACTIVE 只能通过兼容版本修复前进。 */
public interface IRewardControlService {
    boolean schemaReady();
    boolean isActive();
    String mode();
    <T> T legacySource(Supplier<T> action);
    <T> T legacyDrain(Supplier<T> action);
    /** 调用方必须已开启数据库事务，共享锁一直持有到提交。 */
    void requireActiveTransaction();
    Map<String, Object> status();
    Map<String, Object> beginDrain();
    Map<String, Object> importBaseline();
    Map<String, Object> activate();
    Map<String, Object> cancelDrain();
}
