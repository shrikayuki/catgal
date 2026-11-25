/*
 * 阅读说明
 * 游戏作品的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
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

    /** 检索服务先决定 MySQL 或 ES，再批量补充游戏展示数据，避免控制器承担查询分支。 */
    PageDTO<GameVO> pageGames(@Valid GamePageQuery query);

    /** 验证游戏可见性后组装详情，浏览计数通过原子计数与标脏异步同步。 */
    GameVO getGameById(Long id);

    /** 为评论、评价和资源列表提供基础游戏信息，避免重复构造完整详情。 */
    GameVO getGameSimpleById(Long id);

    /** 从已发布候选集合抽取后仍核对当前游戏，缓存是派生数据，状态以数据库为准。 */
    GameVO getRandomGame();
}
