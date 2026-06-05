package com.catgal.server.service;

import com.catgal.common.domain.dto.CreateFolderDTO;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.FolderGameVO;
import com.catgal.server.domain.po.FavoriteFolder;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 收藏夹表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface IFavoriteFolderService extends IService<FavoriteFolder> {

    void createFolder(CreateFolderDTO dto);

    void deleteFolder(Long id);


    Integer getGameCountFromfolder(Long folderId);

    PageDTO<FolderGameVO> getGamesById(Long id, PageQuery query);
}
