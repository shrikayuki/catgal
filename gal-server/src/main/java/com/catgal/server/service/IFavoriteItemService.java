/*
 * 阅读说明
 * 收藏明细的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.catgal.common.domain.dto.FavoriteDTO;
import com.catgal.server.domain.po.FavoriteItem;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.Valid;

/**
 * <p>
 * 收藏明细表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface IFavoriteItemService extends IService<FavoriteItem> {

    /** 先锁并校验本人收藏夹，再锁目标游戏，确认缓存完整后执行一次原子收藏。 */
    Boolean favorite(@Valid FavoriteDTO dto);

    /** 沿用收藏夹到游戏的锁序，允许取消下架目标，缓存快照仍需完整。 */
    Boolean unfavorite(@Valid FavoriteDTO dto);

    /** 读取完整收藏夹快照并按 ID 锁定游戏，清空和计数变化由一次脚本处理。 */
    Boolean unfavoriteBatch(Long folderId);

    /** 消息仅提供校准目标，实际关系始终取已校验的当前状态。 */
    void syncFavorite(Long folderId, Long userId, Long gameId);

    /** 在统一锁序与删除日志保护下删除收藏夹及明细。 */
    void deleteFolder(Long folderId);

    /** 整批先锁齐收藏夹再锁游戏，所有快照通过验证后才替换持久化明细。 */
    void fullSyncFavoriteItemsToDBTask(Integer batchSize);
}
