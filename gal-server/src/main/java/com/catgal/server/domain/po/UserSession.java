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
 * 
 * </p>
 *
 * @author rance
 * @since 2026-06-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_session")
public class UserSession implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long girlId;

    private String sessionId;

    /**
     * 角色编码: emily/yukino/shirasagi
     */
    private String girlCode;

    /**
     * 对话轮数
     */
    private Integer messageCount;

    /**
     * 最后聊天时间
     */
    private LocalDateTime lastChatTime;

    /**
     * 1启用 0禁用
     */
    private Integer status;

    private LocalDateTime createTime;


}
