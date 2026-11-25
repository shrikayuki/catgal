/*
 * 阅读说明
 * 收藏关系的原子变更、删除日志、完整快照与异步落库。
 * 数据库行锁协调生命周期，Lua 协调 Redis 多键，MQ 与定时任务按当前状态校准。
 */
package com.catgal.server.service.impl;

import Message.FavoriteSyncMessage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.catgal.common.autoconfigure.mq.RabbitMqHelper;
import com.catgal.common.domain.dto.FavoriteDTO;
import com.catgal.common.exception.BusinessException;
import com.catgal.common.utils.StringUtils;
import com.catgal.server.domain.po.FavoriteFolder;
import com.catgal.server.domain.po.FavoriteItem;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.FavoriteFolderMapper;
import com.catgal.server.mapper.FavoriteItemMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IFavoriteItemService;
import com.catgal.server.service.RedisDirtySetProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static Message.FavoriteSyncMessage.FAVORITE;
import static Message.FavoriteSyncMessage.UNFAVORITE;
import static com.catgal.common.constants.CountLimitConstant.COUNT_LIMIT_K;
import static com.catgal.common.constants.MqConstants.Exchange.FAVORITE_EXCHANGE;
import static com.catgal.common.constants.MqConstants.Key.FAVORITE_CHANGE_KEY;
import static com.catgal.common.constants.RedisConstant.*;
import static com.catgal.server.redis.RedisScriptLoader.load;

/**
 * 收藏关系的写入、校准和删除入口。
 * 锁序固定为所有收藏夹 ID 升序，再锁所有游戏 ID 升序，最后写明细。
 * Redis 保存尚未落库的当前状态，MQ 与定时任务都只校准当前状态，消息中的旧操作不再重放。
 *
 * @author rance
 * @since 2026-05-01
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FavoriteItemServiceImpl extends ServiceImpl<FavoriteItemMapper, FavoriteItem> implements IFavoriteItemService {
    private static final DefaultRedisScript<Long> CHANGE_SCRIPT = load("lua/favorite/change.lua", Long.class);
    private static final DefaultRedisScript<List> SNAPSHOT_SCRIPT = load("lua/favorite/snapshot.lua", List.class);
    private static final DefaultRedisScript<Long> HYDRATE_SCRIPT = load("lua/favorite/hydrate.lua", Long.class);
    private static final DefaultRedisScript<Long> PREPARE_DELETE_SCRIPT = load("lua/favorite/prepare-delete.lua", Long.class);
    private static final DefaultRedisScript<Long> RESTORE_DELETE_SCRIPT = load("lua/favorite/restore-delete.lua", Long.class);
    private static final DefaultRedisScript<Long> FINISH_DELETE_SCRIPT = load("lua/favorite/finish-delete.lua", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final FavoriteFolderMapper folderMapper;
    private final RabbitMqHelper mqHelper;
    private final FavoriteItemMapper favoriteItemMapper;
    private final GameMapper gameMapper;
    private final CurrentUserProvider currentUserProvider;
    private final RedisDirtySetProcessor dirtySetProcessor;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional
    // 先锁并校验本人收藏夹，再锁目标游戏，确认缓存完整后执行一次原子收藏。
    public Boolean favorite(FavoriteDTO dto) {
        Long userId = currentUserProvider.requireUserId();
        validateRequest(dto);
        FavoriteFolder folder = requireOwnedFolder(dto.getFolderId(), userId);
        Game game = gameMapper.selectForUpdate(dto.getGameId());
        if (game == null || !Objects.equals(game.getStatus(), 1)) {
            throw BusinessException.notFound("游戏不存在或未发布");
        }
        currentSnapshot(folder);
        ensureCounter(game.getId(), game);
        return changeFavorite(folder, dto.getGameId(), FAVORITE);
    }

    @Override
    @Transactional
    // 沿用收藏夹到游戏的锁序，允许取消下架目标，缓存快照仍需完整。
    public Boolean unfavorite(FavoriteDTO dto) {
        Long userId = currentUserProvider.requireUserId();
        validateRequest(dto);
        FavoriteFolder folder = requireOwnedFolder(dto.getFolderId(), userId);
        // 下架或物理删除的游戏仍允许取消，目标缺失时只清理历史关系。
        Game game = gameMapper.selectForUpdate(dto.getGameId());
        currentSnapshot(folder);
        ensureCounter(dto.getGameId(), game);
        return changeFavorite(folder, dto.getGameId(), UNFAVORITE);
    }

    @Override
    @Transactional
    // 读取完整收藏夹快照并按 ID 锁定游戏，清空和计数变化由一次脚本处理。
    public Boolean unfavoriteBatch(Long folderId) {
        FavoriteFolder folder = requireOwnedFolder(folderId, currentUserProvider.requireUserId());
        Snapshot snapshot = currentSnapshot(folder);
        Map<Long, Game> games = lockGames(snapshot.gameIds());
        for (Long gameId : snapshot.gameIds()) ensureCounter(gameId, games.get(gameId));
        // 整个收藏夹一次原子清空，避免逐项执行时中途失败留下半清空状态。
        finishSnapshot(folderId, snapshot, games, "clear");
        return true;
    }

    @Override
    @Transactional
    // 先准备可恢复删除日志，再提交数据库删除，提交后的脚本完成缓存与计数清理。
    public void deleteFolder(Long folderId) {
        FavoriteFolder folder = requireOwnedFolder(folderId, currentUserProvider.requireUserId());
        Snapshot snapshot = currentSnapshot(folder);
        lockGames(relatedGameIds(folderId, snapshot));
        String token = UUID.randomUUID().toString();
        requireScriptSuccess(redisTemplate.execute(PREPARE_DELETE_SCRIPT,
                List.of(hashKey(folderId), markerKey(folderId), FAVORITE_CHANGE_SET_KEY),
                folderId.toString(), folder.getUserId().toString(), token, snapshot.marker()), folderId);
        String journal = "D:" + folder.getUserId() + ":" + snapshot.gameIds().size() + ":" + token;
        // 准备阶段保留 Hash 和计数。数据库提交才做扣减，进程中断后由脏任务续跑。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        redisTemplate.execute(RESTORE_DELETE_SCRIPT, List.of(markerKey(folderId)), journal);
                    } catch (RuntimeException failure) {
                        log.warn("收藏删除回滚日志保留，后续持锁请求恢复, folderId={}", folderId, failure);
                    }
                }
            }
        });
        scheduleDeletedCleanup(folderId);
        favoriteItemMapper.deleteByFolderIds(Set.of(folderId));
        if (folderMapper.deleteById(folderId) != 1) throw BusinessException.conflict("收藏夹删除冲突");
    }

    @Override
    @Transactional
    // 通知只提供定位信息，数据库关系按当前完整快照校准，吸收重复与乱序投递。
    public void syncFavorite(Long folderId, Long userId, Long gameId) {
        FavoriteFolder folder = folderMapper.selectForUpdate(folderId);
        if (folder == null) {
            completeDeletedFolder(folderId);
            return;
        }
        if (!Objects.equals(folder.getUserId(), userId)) {
            throw new AmqpRejectAndDontRequeueException("收藏同步消息的用户与收藏夹归属不符");
        }
        Game game = gameMapper.selectForUpdate(gameId);
        Snapshot snapshot = currentSnapshot(folder);
        var query = Wrappers.<FavoriteItem>lambdaQuery().eq(FavoriteItem::getFolderId, folderId)
                .eq(FavoriteItem::getGameId, gameId).eq(FavoriteItem::getUserId, userId);
        // 当前 Hash 不再包含目标时，迟到的收藏通知同样执行删除；反向乱序也按此规则校准。
        if (game == null || !snapshot.gameIds().contains(gameId)) {
            favoriteItemMapper.delete(query);
        } else if (!favoriteItemMapper.exists(query)) {
            favoriteItemMapper.insert(item(folderId, gameId, userId));
        }
    }

    @Override
    // 整批先锁齐收藏夹再锁游戏，所有快照通过验证后才替换持久化明细。
    public void fullSyncFavoriteItemsToDBTask(Integer batchSize) {
        if (batchSize == null || batchSize <= 0) throw BusinessException.badRequest("同步批次须为正数");
        int processed = dirtySetProcessor.processBatch(FAVORITE_CHANGE_SET_KEY, batchSize, true, claimed -> {
            List<Long> ids = claimed.stream().map(Long::valueOf).sorted().toList();
            Map<Long, FavoriteFolder> folders = new LinkedHashMap<>();
            Map<Long, Snapshot> snapshots = new LinkedHashMap<>();
            Set<Long> games = new TreeSet<>();
            // 必须先锁齐所有收藏夹，再锁游戏，不能夹杂两个层级的锁。
            for (Long id : ids) {
                if (id <= 0) throw new IllegalStateException("无效的收藏夹同步ID");
                folders.put(id, folderMapper.selectForUpdate(id));
            }
            for (Long id : ids) {
                FavoriteFolder folder = folders.get(id);
                Snapshot snapshot = folder == null ? rawSnapshot(id) : currentSnapshot(folder);
                if (snapshot == null && folder == null && databaseItems(id).isEmpty()
                        && !Boolean.TRUE.equals(redisTemplate.hasKey(hashKey(id)))
                        && !Boolean.TRUE.equals(redisTemplate.hasKey(markerKey(id)))) {
                    snapshot = new Snapshot("0", Set.of());
                }
                if (snapshot == null) throw cacheUnavailable(id);
                snapshots.put(id, snapshot);
                games.addAll(relatedGameIds(id, snapshot));
            }
            Map<Long, Game> lockedGames = lockGames(games);
            List<FavoriteItem> replacements = new ArrayList<>();
            for (Long id : ids) {
                Snapshot snapshot = snapshots.get(id);
                FavoriteFolder folder = folders.get(id);
                if (folder == null) {
                    // 旧版遗留孤儿行先提交 SQL 清理，提交后再扣 Redis，避免 SQL 回滚后丢失清理凭据。
                    scheduleDeletedCleanup(id);
                } else {
                    for (Long gameId : snapshot.gameIds()) {
                        if (lockedGames.get(gameId) == null) {
                            // 物理删除目标的派生关系同时从 Hash 移除，避免 HLEN 永久包含已删除游戏。
                            ensureCounter(gameId, null);
                            changeFavorite(folder, gameId, UNFAVORITE);
                        } else {
                            replacements.add(item(id, gameId, folder.getUserId()));
                        }
                    }
                }
            }
            // 全批次验证完成后才删旧行；缓存异常会让整个 SQL 事务回滚并重排任务。
            favoriteItemMapper.deleteByFolderIds(new HashSet<>(ids));
            if (!replacements.isEmpty() && !saveBatch(replacements)) throw new IllegalStateException("收藏明细写入失败");
            for (Long id : ids) {
                if (folders.get(id) != null) {
                    int count = (int) replacements.stream().filter(value -> value.getFolderId().equals(id)).count();
                    if (folderMapper.update(null, Wrappers.<FavoriteFolder>lambdaUpdate()
                            .eq(FavoriteFolder::getId, id).set(FavoriteFolder::getGameCount, count)) != 1) {
                        throw new IllegalStateException("收藏夹计数更新失败");
                    }
                }
            }
        });
        log.info("收藏记录同步完成, 处理收藏夹数={}", processed);
    }

    /** 仅持有已存在收藏夹的行锁时调用，删除准备日志在父记录仍存在时可以安全恢复。 */
    // 区分已加载、删除中和缺失缓存，无未落库任务时才允许从 MySQL 回灌。
    private Snapshot currentSnapshot(FavoriteFolder folder) {
        Snapshot snapshot = rawSnapshot(folder.getId());
        if (snapshot == null) {
            List<FavoriteItem> records = databaseItems(folder.getId());
            List<String> args = new ArrayList<>(List.of(folder.getId().toString(), folder.getUserId().toString()));
            for (FavoriteItem record : records) {
                if (!Objects.equals(record.getUserId(), folder.getUserId()) || record.getGameId() <= 0) {
                    throw cacheUnavailable(folder.getId());
                }
                args.add(record.getGameId().toString());
            }
            requireScriptSuccess(redisTemplate.execute(HYDRATE_SCRIPT,
                    List.of(hashKey(folder.getId()), markerKey(folder.getId()), FAVORITE_CHANGE_SET_KEY,
                            FAVORITE_CHANGE_SET_KEY + ":processing"), args.toArray()), folder.getId());
            snapshot = rawSnapshot(folder.getId());
        }
        if (snapshot == null) throw cacheUnavailable(folder.getId());
        if ((snapshot.ownerId() != null && !snapshot.ownerId().equals(folder.getUserId()))
                || (snapshot.deleting() && !snapshot.marker().split(":")[1].equals(folder.getUserId().toString()))) {
            throw cacheUnavailable(folder.getId());
        }
        if (snapshot.deleting()) {
            requireScriptSuccess(redisTemplate.execute(RESTORE_DELETE_SCRIPT,
                    List.of(markerKey(folder.getId())), snapshot.marker()), folder.getId());
            snapshot = new Snapshot(String.valueOf(snapshot.gameIds().size()), snapshot.gameIds());
        }
        return snapshot;
    }

    /** 只接受有完整性证明且归属一致的快照。存在积压时不得从部分 Hash 或旧数据库反推当前关系。 */
    private Snapshot rawSnapshot(Long folderId) {
        List<?> values = redisTemplate.execute(SNAPSHOT_SCRIPT, List.of(hashKey(folderId), markerKey(folderId)));
        if (values == null || values.size() < 2 || values.size() % 2 != 0
                || !("1".equals(values.get(0).toString()) || "2".equals(values.get(0).toString()))) return null;
        String marker = values.get(1).toString();
        Set<Long> gameIds = new TreeSet<>();
        Long owner = null;
        try {
            for (int index = 2; index < values.size(); index += 2) {
                Long gameId = Long.valueOf(values.get(index).toString());
                Long userId = Long.valueOf(values.get(index + 1).toString());
                if (gameId <= 0 || userId <= 0 || (owner != null && !owner.equals(userId))) return null;
                if (!gameIds.add(gameId)) return null;
                owner = userId;
            }
            if (marker.startsWith("D:") && owner != null && !marker.split(":")[1].equals(owner.toString())) return null;
        } catch (NumberFormatException failure) {
            return null;
        }
        return new Snapshot(marker, Collections.unmodifiableSet(gameIds), owner);
    }

    /**
     * 游戏计数缺失时，持有游戏锁后用 SQL 当前关系加 pending/processing 中的未落库差额重算。
     * 删除日志中的 Hash 在完成扣减前仍计入总数。任何相关脏快照不可验证时保留现场并返回 503。
     */
    private void ensureCounter(Long gameId, Game game) {
        String key = StringUtils.format(FAVORITE_COUNT_KEY, gameId);
        String value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            try {
                long parsed = Long.parseLong(value);
                if (value.matches("[0-9]+") && parsed >= 0 && parsed <= Integer.MAX_VALUE) return;
            } catch (NumberFormatException ignored) { /* 下方按已验证关系重新计算。 */ }
        }
        if (game == null) {
            redisTemplate.opsForValue().set(key, "0");
            return;
        }
        List<FavoriteItem> records = favoriteItemMapper.selectByGameForUpdate(gameId);
        Set<Long> databaseFolders = new HashSet<>();
        for (FavoriteItem record : records) databaseFolders.add(record.getFolderId());
        long count = records.size();
        Set<String> pending = redisTemplate.opsForSet().union(FAVORITE_CHANGE_SET_KEY,
                FAVORITE_CHANGE_SET_KEY + ":processing");
        if (pending == null) throw new IllegalStateException("收藏积压读取失败");
        for (String id : pending) {
            Long folderId = Long.valueOf(id);
            Snapshot snapshot = rawSnapshot(folderId);
            if (snapshot == null) {
                // 名单读取后，已完成任务可能被确认并清掉缓存。再次原子读取两套队列，避免沿用旧名单误判。
                Set<String> remaining = redisTemplate.opsForSet().union(FAVORITE_CHANGE_SET_KEY,
                        FAVORITE_CHANGE_SET_KEY + ":processing");
                if (remaining != null && !remaining.contains(id)) continue;
                throw cacheUnavailable(folderId);
            }
            count += (snapshot.gameIds().contains(gameId) ? 1 : 0) - (databaseFolders.contains(folderId) ? 1 : 0);
        }
        if (count < 0 || count > Integer.MAX_VALUE) throw new IllegalStateException("收藏计数超出范围");
        redisTemplate.opsForValue().set(key, String.valueOf(count));
    }

    // 统一构造 Lua 的 KEYS 与 ARGV，变更关系、完整性标记、计数和脏集合。
    private boolean changeFavorite(FavoriteFolder folder, Long gameId, int operation) {
        Long count = redisTemplate.execute(CHANGE_SCRIPT, List.of(hashKey(folder.getId()), markerKey(folder.getId()),
                        StringUtils.format(FAVORITE_COUNT_KEY, gameId), FAVORITE_CHANGE_SET_KEY, GAME_STATS_COUNT_CHANGE_SET_KEY),
                gameId.toString(), folder.getUserId().toString(), folder.getId().toString(),
                operation == FAVORITE ? "1" : "-1");
        if (count == null) throw new IllegalStateException("收藏操作未返回结果");
        if (count == -2) throw cacheUnavailable(folder.getId());
        if (count == -1) return false;
        if (count <= COUNT_LIMIT_K) {
            // Redis 已原子登记兜底脏任务。MQ 不可用时保留成功操作，等待定时校准。
            try {
                mqHelper.send(FAVORITE_EXCHANGE, FAVORITE_CHANGE_KEY, FavoriteSyncMessage.builder()
                        .userId(folder.getUserId()).folderId(folder.getId()).gameId(gameId).operation(operation).build());
            } catch (RuntimeException failure) {
                log.warn("收藏快速通知失败，等待脏任务校准, folderId={}, gameId={}", folder.getId(), gameId, failure);
            }
        }
        return true;
    }

    // 收藏夹实体已删除时根据持久化清理凭据继续完成派生状态回收。
    private void completeDeletedFolder(Long folderId) {
        Snapshot snapshot = rawSnapshot(folderId);
        if (snapshot == null) {
            if (databaseItems(folderId).isEmpty() && !Boolean.TRUE.equals(redisTemplate.hasKey(hashKey(folderId)))
                    && !Boolean.TRUE.equals(redisTemplate.hasKey(markerKey(folderId)))) return;
            throw cacheUnavailable(folderId);
        }
        Map<Long, Game> games = lockGames(relatedGameIds(folderId, snapshot));
        if (!databaseItems(folderId).isEmpty()) {
            favoriteItemMapper.deleteByFolderIds(Set.of(folderId));
            // SQL 提交失败时快照仍保留，MQ 有限重试或定时任务均可继续处理。
            scheduleDeletedCleanup(folderId);
            return;
        }
        finishDeletedSnapshot(folderId, snapshot, games);
    }

    private void scheduleDeletedCleanup(Long folderId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    var transaction = new TransactionTemplate(transactionManager);
                    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    transaction.executeWithoutResult(status -> {
                        if (folderMapper.selectForUpdate(folderId) == null) completeDeletedFolder(folderId);
                    });
                } catch (RuntimeException failure) {
                    log.warn("孤儿收藏缓存清理等待重试, folderId={}", folderId, failure);
                    redisTemplate.opsForSet().add(FAVORITE_CHANGE_SET_KEY, folderId.toString());
                }
            }
        });
    }

    private void finishDeletedSnapshot(Long folderId, Snapshot snapshot, Map<Long, Game> games) {
        if (!snapshot.deleting() && snapshot.gameIds().isEmpty()) {
            redisTemplate.delete(List.of(hashKey(folderId), markerKey(folderId)));
            return;
        }
        for (Long gameId : snapshot.gameIds()) ensureCounter(gameId, games.get(gameId));
        finishSnapshot(folderId, snapshot, games, "delete");
    }

    private void finishSnapshot(Long folderId, Snapshot snapshot, Map<Long, Game> games, String mode) {
        List<String> keys = new ArrayList<>(List.of(hashKey(folderId), markerKey(folderId),
                FAVORITE_CHANGE_SET_KEY, GAME_STATS_COUNT_CHANGE_SET_KEY));
        List<String> args = new ArrayList<>(List.of(snapshot.marker(), folderId.toString(), mode));
        for (Long gameId : snapshot.gameIds()) {
            keys.add(StringUtils.format(FAVORITE_COUNT_KEY, gameId));
            args.add(gameId.toString());
            args.add(games.get(gameId) == null ? "0" : "1");
        }
        requireScriptSuccess(redisTemplate.execute(FINISH_DELETE_SCRIPT, keys, args.toArray()), folderId);
    }

    private Set<Long> relatedGameIds(Long folderId, Snapshot snapshot) {
        Set<Long> ids = new TreeSet<>(snapshot.gameIds());
        for (FavoriteItem record : databaseItems(folderId)) ids.add(record.getGameId());
        return ids;
    }

    // 游戏 ID 排序后统一获取行锁，与其他收藏写入保持相同层级和顺序。
    private Map<Long, Game> lockGames(Collection<Long> ids) {
        Map<Long, Game> games = new LinkedHashMap<>();
        for (Long id : new TreeSet<>(ids)) games.put(id, gameMapper.selectForUpdate(id));
        return games;
    }

    private List<FavoriteItem> databaseItems(Long folderId) {
        return favoriteItemMapper.selectList(Wrappers.<FavoriteItem>lambdaQuery().eq(FavoriteItem::getFolderId, folderId));
    }

    private FavoriteFolder requireOwnedFolder(Long folderId, Long userId) {
        if (folderId == null || folderId <= 0) throw BusinessException.badRequest("收藏夹ID无效");
        FavoriteFolder folder = folderMapper.selectForUpdate(folderId);
        if (folder == null) throw BusinessException.notFound("收藏夹不存在");
        if (!Objects.equals(folder.getUserId(), userId)) throw BusinessException.forbidden("无权操作");
        return folder;
    }

    private FavoriteItem item(Long folderId, Long gameId, Long ownerId) {
        return new FavoriteItem().setFolderId(folderId).setGameId(gameId).setUserId(ownerId);
    }

    private void validateRequest(FavoriteDTO dto) {
        if (dto == null || dto.getGameId() == null || dto.getGameId() <= 0
                || dto.getFolderId() == null || dto.getFolderId() <= 0) {
            throw BusinessException.badRequest("游戏ID和收藏夹ID须为正数");
        }
    }

    private void requireScriptSuccess(Long result, Long folderId) {
        if (!Long.valueOf(1).equals(result)) throw cacheUnavailable(folderId);
    }

    private String hashKey(Long id) { return StringUtils.format(USER_FAVORITE_KEY, id); }
    private String markerKey(Long id) { return StringUtils.format(USER_FAVORITE_EXPECTED_KEY, id); }

    private BusinessException cacheUnavailable(Long folderId) {
        log.warn("收藏缓存待恢复，保留数据库关系与积压, folderId={}", folderId);
        return new BusinessException(503, 503, "收藏缓存待恢复，请稍后重试");
    }

    private record Snapshot(String marker, Set<Long> gameIds, Long ownerId) {
        Snapshot(String marker, Set<Long> gameIds) { this(marker, gameIds, null); }
        boolean deleting() { return marker.startsWith("D:"); }
    }
}
