package com.catgal.server.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;

import com.catgal.common.enums.PlayStatusEnum;
import com.catgal.common.enums.RecommendLevelEnum;
import com.catgal.common.enums.SpoilerLevelEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 游戏评价表
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("game_review")
public class GameReview implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 评价主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 游戏ID
     */
    private Long gameId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 推荐程度：1-强烈不推荐，2-不推荐，3-中立，4-推荐，5-强烈推荐
     */
    private RecommendLevelEnum recommendLevel;

    /**
     * 评分：1-10分
     */
    private Integer score;

    /**
     * 游玩状态：1-未开始，2-正在通关，3-单线，4-主线，5-全线，6-弃坑
     */
    private PlayStatusEnum playStatus;

    /**
     * 简评，最多520字
     */
    private String briefReview;

    /**
     * 剧透等级：1-无剧透，2-轻微剧透，3-严重剧透
     */
    private SpoilerLevelEnum spoilerLevel;

    /**
     * 点赞数
     */
    private Integer likeCount;

    /**
     * 状态：0-已删除，1-正常
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
