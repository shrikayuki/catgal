/*
 * 阅读说明
 * 定期处理持久化文件清理任务，回收已解绑或上传失败的对象。
 */
package com.catgal.server.task;

import com.catgal.server.service.IStorageFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StorageCleanupTask {
    private final IStorageFileService files;

    @Scheduled(fixedDelayString = "${catgal.storage.cleanup-delay-ms:60000}", initialDelay = 60000)
    public void run() {
        try {
            files.cleanupPending();
        } catch (RuntimeException failure) {
            log.warn("文件清理批次失败, errorType={}", failure.getClass().getSimpleName());
        }
    }
}
