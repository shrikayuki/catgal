package com.catgal.server.task;

import com.catgal.server.service.IRewardOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每秒最多发送一个事件，发送确认最长 5 秒。积压通过管理页观察，调整频率前先测量负载。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RewardOutboxTask {
    private final IRewardOutboxService outbox;
    @Scheduled(fixedDelayString = "${catgal.rewards.outbox-delay-ms:1000}")
    public void dispatch() {
        try { outbox.dispatchOne(); }
        catch (RuntimeException failure) { log.warn("积分 Outbox 轮询失败, type={}", failure.getClass().getSimpleName()); }
    }
}
