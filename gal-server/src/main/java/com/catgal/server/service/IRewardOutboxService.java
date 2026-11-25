package com.catgal.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.catgal.server.domain.po.RewardOutbox;
import java.util.Map;

/** 通知采用至少一次投递，消费者只从 MySQL 读取权威快照并按版本更新 Redis。 */
public interface IRewardOutboxService extends IService<RewardOutbox> {
    void dispatchOne();
    void project(Long eventId);
    Map<String, Object> overview();
    void retry(Long eventId);
}
