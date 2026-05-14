package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.CreateFolderDTO;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.FolderGameVO;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.server.domain.po.FavoriteFolder;
import com.catgal.server.domain.po.FavoriteItem;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.service.IFavoriteFolderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 收藏夹表 服务实现类
 * </p>
 *
 * @author rance
 * @since 2026-05-01
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FavoriteFolderServiceImpl extends ServiceImpl<FavoriteFolderMapper, FavoriteFolder> implements IFavoriteFolderService {

    private final FavoriteFolderMapper folderMapper;
    private final FavoriteItemMapper favoriteItemMapper;
    private final GameMapper gameMapper;

    @Override
    @Transactional
    public void createFolder(CreateFolderDTO dto) {
        Long userId = UserContext.getUserId();
        if (userId == null || dto == null) {
            log.error("用户未登录或参数为空");
            throw new RuntimeException();
        }
        boolean exists = folderMapper.exists(
                new LambdaQueryWrapper<FavoriteFolder>()
                        .eq(FavoriteFolder::getUserId, userId)
                        .eq(FavoriteFolder::getName, dto.getName())
        );

        if (exists) {
            throw new RuntimeException("收藏夹名称已存在");
        }

        FavoriteFolder favoriteFolder = BeanUtils.copyProperties(dto, FavoriteFolder.class);
        favoriteFolder.setUserId(userId);
        save(favoriteFolder);

    }

    @Override
    public void deleteFolder(Long id) {
        Long userId = UserContext.getUserId();

        // 1. 查询收藏夹是否存在
        FavoriteFolder folder = folderMapper.selectById(id);
        if (folder == null) {
            throw new RuntimeException("收藏夹不存在");
        }

        // 2. 校验是否属于当前用户
        if (!folder.getUserId().equals(userId)) {
            throw new RuntimeException("无权删除");
        }

        // 3. 删除收藏夹下的所有收藏项
        favoriteItemMapper.delete(
                new LambdaQueryWrapper<FavoriteItem>()
                        .eq(FavoriteItem::getFolderId, id)
        );

        // 4. 删除收藏夹
        folderMapper.deleteById(id);

        log.info("删除收藏夹成功, id={}, userId={}", id, userId);
    }

    @Override
    public PageDTO<FolderGameVO> getGamesById(Long id, PageQuery query) {
        Long userId = UserContext.getUserId();

        // 1. 查询收藏夹是否存在
        FavoriteFolder folder = folderMapper.selectById(id);
        if (folder == null) {
            throw new RuntimeException("收藏夹不存在");
        }

        // 2. 校验权限
        if (folder.getIsPublic() == 0 && !folder.getUserId().equals(userId)) {
            throw new RuntimeException("无权查看");
        }

        // 3. 分页查询收藏项
        Page<FavoriteItem> page = favoriteItemMapper.selectPage(
                query.toMpPageDefaultSortByCreateTimeDesc(),
                new LambdaQueryWrapper<FavoriteItem>()
                        .eq(FavoriteItem::getFolderId, id)
        );

        if (CollUtils.isEmpty(page.getRecords())) {
            return PageDTO.empty(page);
        }

        // 4. 批量查询游戏
        List<Long> gameIds = page.getRecords().stream()
                .map(FavoriteItem::getGameId)
                .collect(Collectors.toList());

        List<Game> games = gameMapper.selectBatchIds(gameIds);
        Map<Long, Game> gameMap = games.stream()
                .collect(Collectors.toMap(Game::getId, Function.identity()));

        // 5. 转换 VO
        List<FolderGameVO> vos = page.getRecords().stream()
                .map(item -> {
                    Game game = gameMap.get(item.getGameId());
                    if (game == null) return null;

                    FolderGameVO vo = new FolderGameVO();
                    vo.setGameId(game.getId());
                    vo.setGameName(game.getName());
                    vo.setGameCoverUrl(game.getCoverUrl());
                    return vo;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return PageDTO.of(page, vos);
    }


}
