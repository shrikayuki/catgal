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
 * 奖池表
 * </p>
 *
 * @author rance
 * @since 2026-05-25
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("prize_pool")
public class PrizePool implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 奖品名称
     */
    private String prizeName;

    /**
     * 奖品图片URL
     */
    private String prizeImage;

    /**
     * 1-萌萌点 2-道具 3-实物 4-谢谢参与
     */
    private Integer prizeType;

    /**
     * 所属月份
     */
    private String month;

    /**
     * 权重
     */
    private Integer weight;

    /**
     * 总库存
     */
    private Integer totalStock;

    /**
     * 剩余库存
     */
    private Integer remainStock;

    /**
     * 1-启用 0-停用
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;


}
