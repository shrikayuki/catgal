/*
 * 阅读说明
 * MinIO 适配层，实现对象流上传、打开和删除。
 * 服务端生成的 bucket/key 由上层元数据提供，业务授权与生命周期由文件服务处理。
 */
package com.catgal.server.service.impl;

import com.catgal.server.service.IObjectStorageService;
import com.catgal.server.storage.ObjectStorageException;
import io.minio.*;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MinioObjectStorageServiceImpl implements IObjectStorageService {
    private final ObjectProvider<MinioClient> clients;

    private MinioClient client() {
        MinioClient client = clients.getIfAvailable();
        if (client == null) throw new IllegalStateException("Managed storage is disabled");
        return client;
    }

    @Override
    // 按已验证的 bucket/key 和精确字节数上传，关闭调用方传入的流并转换存储异常。
    public void put(String bucket, String key, InputStream content, long size, String contentType) {
        try (InputStream input = content) {
            client().putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(input, size, -1L).contentType(contentType).build());
        } catch (Exception exception) {
            throw new ObjectStorageException(exception);
        }
    }

    @Override
    // 返回对象输入流，HTTP 响应消费完成后负责释放资源。
    public InputStream open(String bucket, String key) {
        try {
            return client().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            throw new ObjectStorageException(exception);
        }
    }

    @Override
    // 删除指定物理对象，持久化清理队列负责失败重试与结果确认。
    public void delete(String bucket, String key) {
        try {
            client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            throw new ObjectStorageException(exception);
        }
    }
}
