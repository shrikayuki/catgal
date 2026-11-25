/*
 * 阅读说明
 * 用户积分余额的数据库映射对象，对应 user_cute_points 表。
 * MyBatis-Plus 通过字段与映射注解读写记录，Controller 使用专用视图承载展示字段。
 */
package com.catgal.server.domain.po;

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
 * 用户萌萌点汇总表
 * </p>
 *
 * @author rance
 * @since 2026-05-23
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_cute_points")
public class UserCutePoints implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    /**
     * 当前萌萌点
     */
    private Integer cutePoints;

    /**
     * 累计获得最高萌萌点
     */
    private Integer totalEarned;

    /**
     * 累计消耗萌萌点
     */
    private Integer totalSpent;

    /** 新事务每次变更递增，Redis 投影以此拒绝旧快照覆盖。 */
    private Long revision;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;


}
