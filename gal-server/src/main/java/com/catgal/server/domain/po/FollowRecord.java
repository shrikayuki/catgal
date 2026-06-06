package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 关注记录表
 * </p>
 *
 * @author rance
 * @since 2026-06-05
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("follow_record")
public class FollowRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 粉丝id
     */
    @TableField("fan_id")
    private Long fanId;

    /**
     * 被关注的id
     */
    @TableField("followed_id")
    private Long followedId;

    @TableField("follow_time")
    private LocalDateTime followTime;

    @TableField("create_time")
    private LocalDateTime createTime;


}
