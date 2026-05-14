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

    GameConnectVO<GameReviewVO> pageGameReview(GameQuery query);

    void addGameReview(@Valid ReviewAddDTO dto);

    void deleteGameReview(Long id);
}
