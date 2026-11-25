/*
 * 阅读说明
 * 游戏评价的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.catgal.common.domain.dto.ReviewAddDTO;
import com.catgal.common.domain.query.GameQuery;
import com.catgal.common.domain.vo.GameConnectVO;
import com.catgal.common.domain.vo.GameReviewVO;
import com.catgal.server.domain.po.GameReview;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.Valid;

/**
 * <p>
 * 游戏评价表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface IGameReviewService extends IService<GameReview> {

    /** 只分页展示正常评价，评分、剧透和游玩进度字段与作者展示数据一起返回。 */
    GameConnectVO<GameReviewVO> pageGameReview(GameQuery query);

    /** 事务内先锁游戏再保存评价，随后按当前可见评价重新聚合平均分和数量。 */
    void addGameReview(@Valid ReviewAddDTO dto);

    /** 本人校验后按游戏、评价的顺序加锁，删除与评分重算在同一数据库事务提交。 */
    void deleteGameReview(Long id);
}
