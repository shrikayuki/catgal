/*
 * 阅读说明
 * 对象存储访问失败的内部异常，业务层再转换为安全的用户文案。
 */
package com.catgal.server.storage;

public class ObjectStorageException extends RuntimeException {
    public ObjectStorageException(Throwable cause) {
        super("Object storage operation failed", cause);
    }
}
