/*
 * 阅读说明
 * 用户机器人的数据库映射对象，对应 ai_bot 表。
 * MyBatis-Plus 通过字段与映射注解读写记录，Controller 使用专用视图承载展示字段。
 */
package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 图片绑定只读取形象、归属和并发版本，聊天配置继续由现有 AI 服务管理。 */
@Data
@TableName("ai_bot")
public class AiBot {
    @TableId
    private Long id;
    private Long userId;
    private String kind;
    private Integer status;
    private String avatarUrl;
    private String portraitUrl;
    private Long revision;
    private LocalDateTime updateTime;
}
