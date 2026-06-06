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
     * 1-签到 2-喂宠物 3-被点赞 4-兑换消耗 5-赠送
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
