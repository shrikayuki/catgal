/*
 * 阅读说明
 * 收藏夹的业务服务接口，Controller 和跨业务调用依赖此契约。
 * 继承 IService 获得实体基础操作，带业务含义的方法由同名 ServiceImpl 实现约束。
 */
package com.catgal.server.service;

import com.catgal.common.domain.dto.CreateFolderDTO;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.FolderGameVO;
import com.catgal.server.domain.po.FavoriteFolder;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Collection;
import java.util.Map;

/**
 * <p>
 * 收藏夹表 服务类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
public interface IFavoriteFolderService extends IService<FavoriteFolder> {

    /** 以当前登录用户为归属创建收藏夹，公开性由请求与服务端校验共同确定。 */
    void createFolder(CreateFolderDTO dto);

    /** 入口委托收藏明细服务，统一维护关系、统计和可恢复删除日志。 */
    void deleteFolder(Long id);


    /** 查询指定收藏夹展示数量，完整缓存与数据库回源按实现规则处理。 */
    Integer getGameCountFromfolder(Long folderId);

    /** Cached positive counts for one page. Missing entries retain the database count. */
    Map<Long, Integer> getGameCountsFromFolders(Collection<Long> folderIds);

    /** 本人可访问私有收藏夹，公开读取仍过滤不可见游戏并返回标准分页。 */
    PageDTO<FolderGameVO> getGamesById(Long id, PageQuery query);
}
