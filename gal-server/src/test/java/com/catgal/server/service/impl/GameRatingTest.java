package com.catgal.server.service.impl;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.domain.dto.ReviewAddDTO;
import com.catgal.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.GameReviewMapper;
import com.catgal.server.mapper.ResourceMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.IAdminOperationLogService;
import com.catgal.server.service.IGameService;
import com.catgal.server.service.IGameStatsService;
import com.catgal.server.service.ILikeRecordService;
import com.catgal.server.service.RedisCounterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameRatingTest {
    @BeforeAll static void initializeMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "rating-test"),
                GameReview.class);
    }
    private final GameMapper games = mock(GameMapper.class);
    private final GameReviewMapper reviews = mock(GameReviewMapper.class);
    private final IGameStatsService stats = mock(IGameStatsService.class);
    private final RedisCounterService counters = mock(RedisCounterService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final IAdminOperationLogService audit = mock(IAdminOperationLogService.class);

    @AfterEach void clearUser() { UserContext.clear(); }

    private GameReviewServiceImpl reviewService() {
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        var service = spy(new GameReviewServiceImpl(users, mock(IGameService.class),
                mock(ILikeRecordService.class), games, stats, counters));
        ReflectionTestUtils.setField(service, "baseMapper", reviews);
        when(games.selectForUpdate(5L)).thenReturn(new Game().setId(5L).setStatus(1));
        return service;
    }

    private ReviewAddDTO newReview() {
        var dto = new ReviewAddDTO();
        dto.setGameId(5L);
        dto.setScore(8);
        return dto;
    }

    @Test void readsPersistedRatingWithoutDependingOnPartialRedisSets() {
        var redis = mock(StringRedisTemplate.class);
        var service = new GameStatsService(redis, games, mock(ResourceMapper.class),
                mock(CommentMapper.class), reviews);
        when(games.selectById(5L)).thenReturn(new Game().setRating(new BigDecimal("8.5")));
        assertEquals(new BigDecimal("8.5"), service.getRating(5L));
        when(games.selectById(5L)).thenReturn(new Game().setRating(BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, service.getRating(5L));
        verifyNoInteractions(redis);
    }

    @Test void absentGameOrRatingReturnsZero() {
        var service = new GameStatsService(mock(StringRedisTemplate.class), games,
                mock(ResourceMapper.class), mock(CommentMapper.class), reviews);
        assertEquals(BigDecimal.ZERO, service.getRating(5L));
        when(games.selectById(5L)).thenReturn(new Game());
        assertEquals(BigDecimal.ZERO, service.getRating(5L));
    }

    @Test void insertionLocksGameAndRefreshesBeforeChangingRedisCounters() {
        var service = reviewService();
        doReturn(true).when(service).save(any(GameReview.class));
        service.addGameReview(newReview());
        var order = inOrder(games, service, counters);
        order.verify(games).selectForUpdate(5L);
        order.verify(service).save(any(GameReview.class));
        order.verify(games).refreshRating(5L);
        order.verify(counters).changeAndMark(anyString(), anyLong(), eq(1L), anyString(), eq(5L));
    }

    @Test void failedInsertionDoesNotRefreshOrChangeCounters() {
        var service = reviewService();
        doReturn(false).when(service).save(any(GameReview.class));
        assertThrows(BusinessException.class, () -> service.addGameReview(newReview()));
        verify(games, never()).refreshRating(anyLong());
        verifyNoInteractions(counters);
    }

    @Test void failedRatingUpdateStopsBeforeRedisMutation() {
        var service = reviewService();
        doReturn(true).when(service).save(any(GameReview.class));
        when(games.refreshRating(5L)).thenThrow(new IllegalStateException("database failure"));
        assertThrows(IllegalStateException.class, () -> service.addGameReview(newReview()));
        verifyNoInteractions(counters);
    }

    @Test void deletionLocksGameBeforeReviewAndRefreshesAfterRemoval() {
        var service = reviewService();
        var review = new GameReview().setId(2L).setGameId(5L).setUserId(6L).setStatus(1);
        when(reviews.selectById(2L)).thenReturn(review);
        when(reviews.selectForUpdate(2L)).thenReturn(review);
        doReturn(true).when(service).removeById(review);
        service.deleteGameReview(2L);
        var order = inOrder(games, reviews, service);
        order.verify(games).selectForUpdate(5L);
        order.verify(reviews).selectForUpdate(2L);
        order.verify(service).removeById(review);
        order.verify(games).refreshRating(5L);
    }

    private AdminContentServiceImpl adminReview(int oldStatus) {
        UserContext.setUser(UserInfo.builder().userId(6L).role(2).build());
        when(reviews.selectById(2L)).thenReturn(new GameReview().setId(2L).setGameId(5L).setStatus(oldStatus));
        when(reviews.selectForUpdate(2L)).thenReturn(new GameReview().setId(2L).setGameId(5L).setStatus(oldStatus));
        when(games.selectForUpdate(5L)).thenReturn(new Game().setId(5L));
        when(reviews.update(isNull(), any())).thenReturn(1);
        when(games.selectBatchIds(anyCollection())).thenReturn(List.of());
        when(users.selectBatchIds(anyCollection())).thenReturn(List.of());
        return new AdminContentServiceImpl(mock(CommentMapper.class), reviews, games, users, stats, counters, audit);
    }

    @Test void hidingAndRestoringReviewRefreshesRating() {
        adminReview(1).updateReviewStatus(2L, 0);
        verify(games).refreshRating(5L);
        clearInvocations(games);
        adminReview(0).updateReviewStatus(2L, 1);
        verify(games).refreshRating(5L);
    }

    @Test void unchangedStatusDoesNotMutateRatingOrCounters() {
        adminReview(1).updateReviewStatus(2L, 1);
        verify(games, never()).refreshRating(anyLong());
        verifyNoInteractions(counters);
        verifyNoInteractions(audit);
    }

    @Test void statusConflictDoesNotRefreshRating() {
        var admin = adminReview(1);
        when(reviews.update(isNull(), any())).thenReturn(0);
        assertThrows(BusinessException.class, () -> admin.updateReviewStatus(2L, 0));
        verify(games, never()).refreshRating(anyLong());
        verifyNoInteractions(counters);
        verifyNoInteractions(audit);
    }

    @Test void auditFailurePreventsReviewCounterMutation() {
        var admin = adminReview(1);
        doThrow(new IllegalStateException("isolated audit failure")).when(audit)
                .appendContentStatusChange(6L, "review", 2L, 5L, 1, 0);
        assertThrows(IllegalStateException.class, () -> admin.updateReviewStatus(2L, 0));
        verifyNoInteractions(counters);
    }

    @Test void allWritersUseReadCommittedTransactions() throws Exception {
        assertEquals(Isolation.READ_COMMITTED, GameReviewServiceImpl.class
                .getMethod("addGameReview", ReviewAddDTO.class).getAnnotation(Transactional.class).isolation());
        assertEquals(Isolation.READ_COMMITTED, GameReviewServiceImpl.class
                .getMethod("deleteGameReview", Long.class).getAnnotation(Transactional.class).isolation());
        assertEquals(Isolation.READ_COMMITTED, AdminContentServiceImpl.class
                .getMethod("updateReviewStatus", Long.class, Integer.class).getAnnotation(Transactional.class).isolation());
    }
}
