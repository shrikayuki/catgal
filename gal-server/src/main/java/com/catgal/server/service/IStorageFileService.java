/*
 * 阅读说明
 * 托管文件的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.catgal.server.domain.po.StorageFile;
import com.catgal.server.storage.AiBotImageKind;
import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;

public interface IStorageFileService extends IService<StorageFile> {
    /** 上传并绑定当前用户头像，返回应用可读取的业务路径。 */
    String updateAvatar(MultipartFile file);
    /** 上传并替换游戏封面，使用当前 revision 防止覆盖其他管理员变更。 */
    UpdateResult updateGameCover(long gameId, Long revision, MultipartFile file);
    /** 清除游戏当前封面引用，旧文件进入持久化回收流程。 */
    UpdateResult clearGameCover(long gameId, Long revision);
    /** 替换本人机器人头像或立绘，校验归属和当前版本。 */
    UpdateResult updateAiBotImage(long botId, AiBotImageKind kind, Long revision, MultipartFile file);
    /** 清除本人机器人图片引用，并安排旧对象回收。 */
    UpdateResult clearAiBotImage(long botId, AiBotImageKind kind, Long revision);
    /** 读取当前公开头像，已替换文件会在业务引用检查中失效。 */
    Download openAvatar(String fileId);
    /** 读取当前游戏封面，发布状态与当前管理员身份决定可见性。 */
    Download openGameCover(String fileId);
    /** 读取本人机器人当前图片，接口私有权限由绑定策略执行。 */
    Download openAiBotImage(AiBotImageKind kind, String fileId);
    /** 尝试领取并完成指定文件的回收，失败保留可重试状态。 */
    void cleanup(String fileId);
    /** 有界扫描到期清理任务，逐项失败可在后续周期重试。 */
    void cleanupPending();

    record Download(InputStream content, long size, String contentType) {}
    record UpdateResult(String fileId, String url, Long revision) {}
}
