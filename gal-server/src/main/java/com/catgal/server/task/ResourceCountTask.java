package com.catgal.server.task;

import com.catgal.server.service.IResourceCountService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 固定间隔处理持久任务；重启或 Redis 恢复后自动续跑，单轮数量由 Service 限定。 */
@Component
@RequiredArgsConstructor
public class ResourceCountTask {
    private final IResourceCountService resourceCountService;

    @Scheduled(fixedDelayString = "${resource.count-sync-delay-ms:2000}")
    public void synchronize() {
        resourceCountService.synchronizePending();
    }
}
