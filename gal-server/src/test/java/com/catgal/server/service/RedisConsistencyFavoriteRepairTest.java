package com.catgal.server.service;

import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.vo.admin.CounterValuesVO;
import com.catgal.server.domain.vo.admin.GameStatsConsistencyVO;
import com.catgal.server.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;

import static com.catgal.common.constants.RedisConstant.FAVORITE_CHANGE_SET_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class RedisConsistencyFavoriteRepairTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final GameMapper games = mock(GameMapper.class);
    private final IGameStatsService stats = mock(IGameStatsService.class);
    private final SetOperations<String, String> sets = mock(SetOperations.class);
    private final RedisConsistencyAdminService service = spy(new RedisConsistencyAdminService(redis, stats, games,
            mock(ResourceMapper.class), mock(CommentMapper.class), mock(GameReviewMapper.class), mock(FavoriteItemMapper.class),
            mock(IResourceCountService.class)));

    @Test void pendingFavoriteStateIsCheckedAfterTheGameLockAndPreventsRepair() {
        when(games.selectForUpdate(20L)).thenReturn(new Game().setId(20L));
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.size(FAVORITE_CHANGE_SET_KEY)).thenReturn(1L);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.repairGame(20L)).getHttpStatus());
        var order = inOrder(games, sets);
        order.verify(games).selectForUpdate(20L); order.verify(sets).size(FAVORITE_CHANGE_SET_KEY);
        verify(games, never()).updateById(any(Game.class)); verify(redis, never()).opsForValue();
    }

    @Test void repairOnlyUpdatesTheLockedGameWithoutEnteringAGlobalStatsBatch() {
        when(games.selectForUpdate(20L)).thenReturn(new Game().setId(20L));
        when(games.updateById(any(Game.class))).thenReturn(1);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        CounterValuesVO value = CounterValuesVO.builder().favoriteCount(2).commentCount(3).reviewCount(4)
                .resourceCount(5).viewCount(6).downloadCount(7).build();
        GameStatsConsistencyVO snapshot = GameStatsConsistencyVO.builder().gameId(20L)
                .database(value).redis(value).relationTruth(value).build();
        doReturn(snapshot).when(service).inspectGame(20L);
        assertSame(snapshot, service.repairGame(20L));
        verify(games).updateById(argThat((Game game) -> game.getId() == 20L && game.getFavoriteCount() == 2
                && game.getViewCount() == 6 && game.getDownloadCount() == 7));
        verifyNoInteractions(stats);
    }
}
