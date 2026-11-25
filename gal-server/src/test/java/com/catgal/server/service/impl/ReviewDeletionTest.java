package com.catgal.server.service.impl;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.GameReviewMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

class ReviewDeletionTest {
    private final GameReviewMapper mapper = mock(GameReviewMapper.class);
    private final IGameStatsService stats = mock(IGameStatsService.class);
    private final RedisCounterService counters = mock(RedisCounterService.class);
    private final GameMapper games = mock(GameMapper.class);
    private final GameReviewServiceImpl service = spy(new GameReviewServiceImpl(mock(UserMapper.class),
            mock(IGameService.class), mock(ILikeRecordService.class), games, stats, counters));

    @AfterEach void clear() { UserContext.clear(); }

    private void remove(int status) {
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        GameReview review = new GameReview().setId(2L).setUserId(6L).setGameId(5L).setStatus(status);
        when(mapper.selectById(2L)).thenReturn(review);
        when(games.selectForUpdate(5L)).thenReturn(new Game().setId(5L));
        when(mapper.selectForUpdate(2L)).thenReturn(review);
        when(stats.getGameReviewCount(5L)).thenReturn(3);
        doReturn(true).when(service).removeById(review);
        service.deleteGameReview(2L);
        verify(mapper).selectForUpdate(2L);
        verify(games).refreshRating(5L);
    }

    @Test void deletingHiddenReviewDoesNotDecrementVisibleCountTwice() {
        remove(0);
        verifyNoInteractions(counters);
    }

    @Test void deletingVisibleReviewDecrementsExactlyOnce() {
        remove(1);
        verify(counters).changeAndMark("review:count:5", 3L, -1L, "game:stats:count:change", 5L);
        verifyNoMoreInteractions(counters);
    }
}
