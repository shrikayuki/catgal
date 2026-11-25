/*
 * 阅读说明
 * 萌萌点流水的数据库映射对象，对应 cute_points_record 表。
 * MyBatis-Plus 通过字段与映射注解读写记录，Controller 使用专用视图承载展示字段。
 */
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
 * 萌萌点流水表
 * </p>
 *
 * @author rance
 * @since 2026-05-23
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("cute_points_record")
public class CutePointsRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /**
     * 变动萌萌点
     */
    private Integer cutePoints;

    /**
     * 新流水使用 PointsType.code，例如 1 签到、10 抽奖消耗。
     * 历史记录保留原值，切换不会按旧字段注释批量重解释历史业务。
     */
    private Integer type;

    /**
     * 唯一业务
     */
    private String bizId;

    private Integer beforePoints;

    private Integer afterPoints;

    private String remark;

    private LocalDateTime createTime;


}
