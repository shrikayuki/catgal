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

    Boolean favorite(@Valid FavoriteDTO dto);

    Boolean unfavorite(@Valid FavoriteDTO dto);

    Boolean unfavoriteBatch(Long folderId);

    void fullSyncFavoriteItemsToDBTask(Integer batchSize);
}
