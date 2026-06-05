package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * AI老婆角色表
 * </p>
 *
 * @author rance
 * @since 2026-06-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("ai_girl")
public class AiGirl implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 角色编码: emily/yukino/shirasagi
     */
    private String girlCode;

    /**
     * 角色名称: 艾米丽
     */
    private String name;

    /**
     * 头像URL
     */
    private String avatarUrl;

    /**
     * 系统提示词（人设）
     */
    private String systemPrompt;

    /**
     * 点赞数
     */
    private Long likeCount;

    /**
     * 1启用 0禁用
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;


}
