/*
 * 阅读说明
 * 收藏夹创建、访问范围与分页展示。
 * 删除委托收藏明细服务，整页计数使用 Pipeline 并校验完整性。
 */
package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.catgal.common.context.UserContext;
import com.catgal.common.domain.dto.CreateFolderDTO;
import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.vo.FolderGameVO;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.BeanUtils;
import com.catgal.common.utils.CollUtils;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.FavoriteFolder;
import com.catgal.server.domain.po.FavoriteItem;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.service.IFavoriteFolderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.server.service.IFavoriteItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.catgal.common.constants.RedisConstant.USER_FAVORITE_KEY;
import static com.catgal.common.constants.RedisConstant.USER_FAVORITE_EXPECTED_KEY;
import static com.catgal.server.redis.RedisScriptLoader.load;

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

    private static final DefaultRedisScript<Long> COUNT_SCRIPT = load("lua/favorite/count.lua", Long.class);

    private final FavoriteFolderMapper folderMapper;
    private final FavoriteItemMapper favoriteItemMapper;
    private final GameMapper gameMapper;
    private final IFavoriteItemService favoriteService;
    private final StringRedisTemplate redisTemplate;


    @Override
    @Transactional
    // 以当前登录用户为归属创建收藏夹，公开性由请求与服务端校验共同确定。
    public void createFolder(CreateFolderDTO dto) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw BusinessException.unauthorized("用户未登录");
        }
        if (dto == null) {
            throw BusinessException.badRequest("请求参数不能为空");
        }
        boolean exists = folderMapper.exists(
                new LambdaQueryWrapper<FavoriteFolder>()
                        .eq(FavoriteFolder::getUserId, userId)
                        .eq(FavoriteFolder::getName, dto.getName())
        );

        if (exists) {
            throw BusinessException.conflict("收藏夹名称已存在");
        }

        FavoriteFolder favoriteFolder = BeanUtils.copyProperties(dto, FavoriteFolder.class);
        favoriteFolder.setUserId(userId);
        favoriteFolder.setIsPublic(Boolean.TRUE.equals(dto.getIsPublic()) ? 1 : 0);
        save(favoriteFolder);

    }

    @Override
    // 入口委托收藏明细服务，统一维护关系、统计和可恢复删除日志。
    public void deleteFolder(Long id) {
        // 生命周期集中在收藏明细服务，删除和并发收藏共用同一个数据库锁序。
        favoriteService.deleteFolder(id);
    }

    @Override
    public Integer getGameCountFromfolder(Long folderId) {
        if (folderId == null) return -1;
        return getGameCountsFromFolders(List.of(folderId)).getOrDefault(folderId, -1);
    }

    @Override
    // 整页 Pipeline 读取计数与完整性标记，缺失或受损结果由调用方保留数据库统计。
    public Map<Long, Integer> getGameCountsFromFolders(Collection<Long> folderIds) {
        if (folderIds == null || folderIds.isEmpty()) return Map.of();
        List<Long> ids = folderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        List<Object> sizes;
        try {
            sizes = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Long id : ids) {
                    byte[] key = redisTemplate.getStringSerializer()
                            .serialize(StringUtils.format(USER_FAVORITE_KEY, id));
                    byte[] marker = redisTemplate.getStringSerializer()
                            .serialize(StringUtils.format(USER_FAVORITE_EXPECTED_KEY, id));
                    // 每个夹的条数与完整性证明在同一脚本读取，整页仍只需一次 Pipeline 往返。
                    connection.scriptingCommands().eval(COUNT_SCRIPT.getScriptAsString().getBytes(StandardCharsets.UTF_8),
                            ReturnType.INTEGER, 2, key, marker);
                }
                return null;
            });
        } catch (DataAccessException exception) {
            log.warn("批量读取收藏夹计数失败，使用数据库计数, errorType={}",
                    exception.getClass().getSimpleName());
            return Map.of();
        }
        if (sizes == null || sizes.size() != ids.size()) {
            log.warn("收藏夹计数批量读取结果不完整，使用数据库计数");
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            if (sizes.get(index) instanceof Long size && size >= 0 && size <= Integer.MAX_VALUE) {
                counts.put(ids.get(index), size.intValue());
            }
        }
        return counts;
    }

    @Override
    // 本人可访问私有收藏夹，公开读取仍过滤不可见游戏并返回标准分页。
    public PageDTO<FolderGameVO> getGamesById(Long id, PageQuery query) {
        Long userId = UserContext.getUserId();

        // 1. 查询收藏夹是否存在
        FavoriteFolder folder = folderMapper.selectById(id);
        if (folder == null) {
            throw BusinessException.notFound("收藏夹不存在");
        }

        // 2. 校验权限
        if (folder.getIsPublic() == 0 && !folder.getUserId().equals(userId)) {
            throw BusinessException.forbidden("无权查看");
        }

        // 3. 分页查询收藏项
        Page<FavoriteItem> page = favoriteItemMapper.selectPage(
                query.toMpPageDefaultSortByCreateTimeDesc(),
                new LambdaQueryWrapper<FavoriteItem>()
                        .eq(FavoriteItem::getFolderId, id)
                        .apply("EXISTS (SELECT 1 FROM game g WHERE g.id = favorite_item.game_id AND g.status = 1)")
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
                    vo.setGameCoverUrl(com.catgal.server.storage.FileAccessPaths.gameCover(game));
                    return vo;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return PageDTO.of(page, vos);
    }


}
