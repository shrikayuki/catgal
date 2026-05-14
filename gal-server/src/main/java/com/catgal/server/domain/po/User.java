package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.*;
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
    
    @Schema(description = "手机号", example = "13800138000")
    private String phone;
    
    @Schema(description = "用户签名，最大107字符", example = "这是一个喜欢Galgame的玩家", maxLength = 107)
    private String signature;
    
    @Schema(description = "头像URL", example = "https://example.com/avatar.jpg")
    private String avatarUrl;
    
    @Schema(description = "用户身份：0-普通用户，1-创作者，2-管理员", example = "0", allowableValues = {"0", "1", "2"})
    private Integer role;
    
    @Schema(description = "创建时间", example = "2024-01-01 12:00:00")
    private LocalDateTime createTime;
    
    @Schema(description = "更新时间", example = "2024-01-01 12:00:00")
    private LocalDateTime updateTime;
}