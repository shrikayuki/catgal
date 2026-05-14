package com.catgal.server.service;

import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.GamePageQuery;
import com.catgal.common.domain.vo.GameVO;
import com.catgal.server.domain.po.Game;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.Valid;

/**
 * <p>
 * 游戏主表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface IGameService extends IService<Game> {

    PageDTO<GameVO> pageGames(@Valid GamePageQuery query);

    GameVO getGameById(Long id);

    GameVO getGameSimpleById(Long id);
}
