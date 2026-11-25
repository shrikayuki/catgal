/*
 * 阅读说明
 * 社区用户的数据库映射对象，对应 user 表。
 * MyBatis-Plus 通过字段与映射注解读写记录，Controller 使用专用视图承载展示字段。
 */
package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("user")
@Schema(description = "用户实体")
public class User {


    
    @TableId(type = IdType.AUTO)
    @Schema(description = "用户主键ID", example = "1")
    private Long id;
    
    @Schema(description = "用户名，唯一，最大17字符", example = "玩家小明", maxLength = 17)
    private String username;
    
    @Schema(description = "密码（加密存储）", example = "$2a$10$xxx", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String password;

    /** 修改密码与版本递增属于同一事务，旧凭据版本随即失效。 */
    @JsonIgnore
    @Schema(hidden = true)
    private Long authVersion;
    
    @Schema(description = "手机号", example = "13800138000")
    private String phone;
    
    @Schema(description = "用户签名，最大107字符", example = "这是一个喜欢Galgame的玩家", maxLength = 107)
    private String signature;
    
    @Schema(description = "头像URL", example = "https://example.com/avatar.jpg")
    private String avatarUrl;

    @Schema(description = "托管头像文件ID")
    private String avatarFileId;
    
    @Schema(description = "用户身份：0-普通用户，1-创作者，2-管理员", example = "0", allowableValues = {"0", "1", "2"})
    private Integer role;

    @Schema(description = "数据库账号状态，0封禁、1正常，定时封禁按截止时间判断有效状态")
    private Integer status;

    @Schema(description = "最近一次封禁原因")
    private String banReason;

    @Schema(description = "封禁截止北京时间，封禁状态下为空表示永久")
    private LocalDateTime bannedUntil;
    
    @Schema(description = "创建时间", example = "2024-01-01 12:00:00")
    private LocalDateTime createTime;
    
    @Schema(description = "更新时间", example = "2024-01-01 12:00:00")
    private LocalDateTime updateTime;
}
