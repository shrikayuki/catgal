/*
 * 阅读说明
 * 对象存储的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 接口收敛业务能力，具体持久化、缓存或外部访问由实现类承担。
 */
package com.catgal.server.service;

import java.io.InputStream;

public interface IObjectStorageService {
    /** Consumes and closes the stream. Size is the exact byte count. */
    void put(String bucket, String key, InputStream content, long size, String contentType);
    /** 打开物理对象读取流，调用方在响应结束或失败后释放资源。 */
    InputStream open(String bucket, String key);
    /** 幂等删除物理对象，业务清理状态与重试由上层文件服务维护。 */
    void delete(String bucket, String key);
}
