/*
 * 阅读说明
 * 托管文件的数据库映射对象，对应 storage_file 表。
 * MyBatis-Plus 通过字段与映射注解读写记录，Controller 使用专用视图承载展示字段。
 */
package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("storage_file")
public class StorageFile {
    @TableId
    /** 公开接口使用的不透明文件定位 ID，访问权限仍由业务引用策略判断。 */
    private String id;
    /** 上传文件的用户，用于归属校验与后续业务追溯。 */
    private Long ownerId;
    /** 文件业务用途，用来选择头像、封面或机器人图片绑定策略。 */
    private String purpose;
    /** 文件归属的业务实体 ID，与 purpose 一起定位引用源。 */
    private Long businessId;
    /** 对象存储的桶名，物理位置由本元数据统一管理。 */
    private String bucket;
    /** 服务端生成的对象键，包含用途、日期、业务 ID 和随机文件 ID。 */
    private String objectKey;
    private String contentType;
    private Long sizeBytes;
    /** 文件内容摘要，用于校验与记录内容身份。 */
    private String sha256;
    /** UPLOADING、READY、CLEANUP_PENDING、DELETED 构成文件生命周期。 */
    private String status;
    /** 清理尝试轮次，最终确认时作为条件以区分并发清理任务。 */
    private Integer cleanupAttempts;
    /** 下次允许清理的时间，同时给进行中的上传保留恢复窗口。 */
    private LocalDateTime nextAttemptAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
