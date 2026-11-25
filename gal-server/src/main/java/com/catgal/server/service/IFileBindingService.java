/*
 * 阅读说明
 * 业务图片引用的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 接口收敛业务能力，具体持久化、缓存或外部访问由实现类承担。
 */
package com.catgal.server.service;

import com.catgal.server.domain.po.StorageFile;

/** Business-specific reference locking, authorization and visibility for managed images. */
public interface IFileBindingService {
    /** 文件业务用途代码，用于选择具体绑定策略。 */
    String purpose();
    /** 对象键使用的业务前缀，物理路径由上传服务统一生成。 */
    String objectPrefix();
    /** 将文件 ID 转成业务读取路径，访问时仍执行当前引用与权限判断。 */
    String publicPath(String fileId);
    /** 在业务事务内锁定引用源并读取当前文件 ID 和 revision。 */
    Reference lockReference(long businessId);
    /** 查询当前引用，供上传结果不明确时复核是否已经成功提交。 */
    Reference currentReference(long businessId);
    /** 检查上传者身份、当前业务状态和修订号，引用行由调用方先行加锁。 */
    void authorizeUpload(long businessId, long uploaderId, Long revision, Reference reference);
    /** 更新业务源中的文件 ID，并按业务类型返回推进后的修订号。 */
    Long replaceReference(long businessId, String fileId, Long revision);
    /** 只返回符合当前引用、就绪状态和访问权限的文件元数据。 */
    StorageFile visibleFile(String fileId);

    record Reference(String fileId, Long revision) {}
}
