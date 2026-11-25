package com.catgal.server.catalog;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.domain.query.ScrollPageQuery;
import com.catgal.common.domain.query.UserHomeQueryDTO;
import com.catgal.common.domain.vo.CommentVO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.domain.query.admin.AdminContentOperationLogQuery;
import com.catgal.server.domain.query.admin.AdminOperationLogQuery;
import com.catgal.server.domain.vo.admin.AdminOperationLogVO;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.GameReviewMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.IAdminContentService;
import com.catgal.server.service.IAdminOperationLogService;
import com.catgal.server.service.IGameService;
import com.catgal.server.service.IGameStatsService;
import com.catgal.server.service.ILikeRecordService;
import com.catgal.server.service.RedisCounterService;
import com.catgal.server.service.impl.AdminContentServiceImpl;
import com.catgal.server.service.impl.AdminOperationLogServiceImpl;
import com.catgal.server.service.impl.CommentServiceImpl;
import com.catgal.server.service.impl.UserAccountStatusServiceImpl;
import com.catgal.server.service.userhome.UserCommentTabQueryStrategy;
import com.catgal.server.service.userhome.UserHomeGameLookup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 使用真实 MySQL 验证可见性和 MyBatis 映射，测试账号、游戏和触发器产生的记录一并回滚。
@SpringJUnitConfig({CatalogDatabaseLiveTest.Config.class, CommentVisibilityDatabaseLiveTest.ModerationConfig.class})
@EnabledIfEnvironmentVariable(named = "CATGAL_CATALOG_DB_TEST", matches = "1")
@Transactional
class CommentVisibilityDatabaseLiveTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CommentMapper comments;
    @Autowired private GameMapper games;
    @Autowired private UserMapper users;
    @Autowired private GameReviewMapper reviews;
    @Autowired private IAdminContentService moderation;
    @Autowired private IAdminOperationLogService audit;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void countsOnlyReachableThreadsAndKeepsVisibleRepliesToHiddenIntermediateComments() {
        var fixture = fixture();
        Long root = comment(fixture, null, null, 1);
        Long hiddenReply = comment(fixture, root, root, 0);
        comment(fixture, root, hiddenReply, 1);
        Long hiddenRoot = comment(fixture, null, null, 0);
        comment(fixture, hiddenRoot, hiddenRoot, 1);
        comment(fixture, 0L, 0L, 1); // 旧数据可能保留找不到主评论的回复。

        assertEquals(2, comments.countVisibleInGame(fixture.gameId()));
        Map<Long, Integer> grouped = comments.countVisibleInGames(List.of(fixture.gameId(), 0L)).stream()
                .collect(Collectors.toMap(CommentMapper.GameCommentCount::gameId, CommentMapper.GameCommentCount::commentCount));
        assertEquals(Map.of(fixture.gameId(), 2), grouped);

        assertEquals(1, comments.updateById(new Comment().setId(root).setStatus(0)));
        assertEquals(0, comments.countVisibleInGame(fixture.gameId()));
        assertEquals(1, comments.updateById(new Comment().setId(root).setStatus(1)));
        assertEquals(2, comments.countVisibleInGame(fixture.gameId()));
    }

    @Test
    void directReplyAndPublicProfileFollowRootAndGameVisibility() {
        var fixture = fixture();
        Long root = comment(fixture, null, null, 1);
        Long reply = comment(fixture, root, root, 1);
        comment(fixture, root, reply, 1);
        Long hiddenRoot = comment(fixture, null, null, 0);
        Long unreachable = comment(fixture, hiddenRoot, hiddenRoot, 1);
        comment(fixture, hiddenRoot, unreachable, 1);

        IGameService gameLookup = mock(IGameService.class);
        when(gameLookup.getById(fixture.gameId())).thenReturn(games.selectById(fixture.gameId()));
        CommentServiceImpl service = new CommentServiceImpl(users, gameLookup, mock(ILikeRecordService.class),
                games);
        ReflectionTestUtils.setField(service, "baseMapper", comments);
        assertEquals(1L, service.getChildComments(reply, new PageQuery()).getTotal());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.getChildComments(unreachable, new PageQuery())).getHttpStatus());

        var profile = new UserCommentTabQueryStrategy(service, new UserHomeGameLookup(games));
        assertEquals(3L, profile.query(new UserHomeQueryDTO(), fixture.userId(), false).getTotal());
        assertEquals(6L, profile.query(new UserHomeQueryDTO(), fixture.userId(), true).getTotal());
        assertEquals(1, games.updateById(new Game().setId(fixture.gameId()).setStatus(2)));
        assertEquals(0L, profile.query(new UserHomeQueryDTO(), fixture.userId(), false).getTotal());
        assertEquals(6L, profile.query(new UserHomeQueryDTO(), fixture.userId(), true).getTotal());
    }

    @Test
    void persistedCountMatchesVisibleThreadsAndGeneralSyncCannotOverwriteIt() {
        var fixture = fixture();
        Long root = comment(fixture, null, null, 1);
        comment(fixture, root, root, 1);
        comment(fixture, null, null, 1);

        games.selectForUpdate(fixture.gameId());
        games.refreshCommentCount(fixture.gameId());
        assertEquals(3, games.selectById(fixture.gameId()).getCommentCount());
        assertEquals(1, comments.updateById(new Comment().setId(root).setStatus(0)));
        games.refreshCommentCount(fixture.gameId());
        games.batchUpdateGameStats(List.of(new Game().setId(fixture.gameId()).setCommentCount(99)
                .setFavoriteCount(0).setViewCount(0).setReviewCount(0).setDownloadCount(0)));
        assertEquals(1, games.selectById(fixture.gameId()).getCommentCount());
    }

    @Test
    void scrollRepliesSkipNewerRowsBetweenPagesAndRejectInvalidCursor() {
        var fixture = fixture();
        Long root = comment(fixture, null, null, 1);
        Long oldest = comment(fixture, root, root, 1);
        comment(fixture, root, root, 0);
        Long latest = comment(fixture, root, root, 1);
        IGameService gameLookup = mock(IGameService.class);
        when(gameLookup.getById(fixture.gameId())).thenReturn(games.selectById(fixture.gameId()));
        CommentServiceImpl service = new CommentServiceImpl(users, gameLookup, mock(ILikeRecordService.class), games);
        ReflectionTestUtils.setField(service, "baseMapper", comments);
        ScrollPageQuery query = new ScrollPageQuery();
        query.setPageSize(1);
        var first = service.scrollThreadReplies(root, query);
        assertEquals(List.of(latest), first.list().stream().map(CommentVO::getId).toList());
        assertTrue(first.hasMore());

        comment(fixture, root, root, 1);
        query.setCursor(first.nextCursor());
        var second = service.scrollThreadReplies(root, query);
        assertEquals(List.of(oldest), second.list().stream().map(CommentVO::getId).toList());
        assertFalse(second.hasMore());
        assertNull(second.nextCursor());
        query.setCursor("invalid");
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.scrollThreadReplies(root, query)).getHttpStatus());
    }

    @Test
    void recordsEachChangedStatusOnceAndKeepsUserAndContentAuditSeparate() {
        var fixture = fixture();
        Long root = comment(fixture, null, null, 1);
        var review = new GameReview().setGameId(fixture.gameId()).setUserId(fixture.userId())
                .setScore(8).setRecommendLevel(4).setPlayStatus(5).setSpoilerLevel(1)
                .setBriefReview("仅用于测试的评价正文").setStatus(1);
        assertEquals(1, reviews.insert(review));
        UserContext.setUser(UserInfo.builder().userId(fixture.userId()).role(2).build());
        try {
            moderation.updateCommentStatus(root, 0);
            moderation.updateCommentStatus(root, 0);
            moderation.updateReviewStatus(review.getId(), 0);
            moderation.updateReviewStatus(review.getId(), 1);
            moderation.updateCommentStatus(root, 1);
            audit.appendUserChange(fixture.userId(), "USER_ROLE_CHANGE", fixture.userId(),
                    Map.of("role", 0), Map.of("role", 1), null);

            var query = new AdminContentOperationLogQuery();
            query.setAdminUserId(fixture.userId());
            var content = audit.pageContentOperations(query);
            assertEquals(4L, content.getTotal());
            assertEquals(List.of("COMMENT_RESTORE", "REVIEW_RESTORE", "REVIEW_HIDE", "COMMENT_HIDE"),
                    content.getList().stream().map(AdminOperationLogVO::getOperation).toList());
            query.setTargetType("review");
            query.setTargetId(review.getId());
            query.setOperation("REVIEW_HIDE");
            var hidden = audit.pageContentOperations(query).getList().get(0);
            assertEquals("review", hidden.getTargetType());
            assertEquals(review.getId().toString(), hidden.getTargetId());
            assertEquals(Map.of("gameId", fixture.gameId().toString(), "status", 1), hidden.getBeforeData());
            assertEquals(Map.of("gameId", fixture.gameId().toString(), "status", 0), hidden.getAfterData());
            query.setRequestId(hidden.getRequestId());
            assertEquals(1L, audit.pageContentOperations(query).getTotal());
            query.setRequestId("different-trace");
            assertEquals(0L, audit.pageContentOperations(query).getTotal());

            var userQuery = new AdminOperationLogQuery();
            userQuery.setAdminUserId(fixture.userId());
            var userOperations = audit.pageUserOperations(userQuery);
            assertEquals(1L, userOperations.getTotal());
            assertNull(userOperations.getList().get(0).getTargetType());
            assertEquals(1, games.selectById(fixture.gameId()).getCommentCount());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void auditFailureRollsBackCommentStateAndCountInRealDatabase() {
        var transaction = new TransactionTemplate(transactions);
        var target = transaction.execute(tx -> {
            var fixture = fixture();
            Long root = comment(fixture, null, null, 1);
            games.refreshCommentCount(fixture.gameId());
            return new ModeratedComment(fixture, root);
        });
        assertNotNull(target);
        var fixture = target.fixture();
        long root = target.id();
        var writer = AopTestUtils.<AdminOperationLogServiceImpl>getUltimateTargetObject(audit);
        try {
            UserContext.setUser(UserInfo.builder().userId(fixture.userId()).role(2).build());
            doAnswer(invocation -> {
                invocation.callRealMethod();
                throw new IllegalStateException("isolated audit failure");
            }).when(writer).appendContentStatusChange(fixture.userId(), "comment", root, fixture.gameId(), 1, 0);

            assertThrows(IllegalStateException.class, () -> moderation.updateCommentStatus(root, 0));
            assertEquals(1, comments.selectById(root).getStatus());
            assertEquals(1, games.selectById(fixture.gameId()).getCommentCount());
            var query = new AdminContentOperationLogQuery();
            query.setAdminUserId(fixture.userId());
            query.setTargetType("comment");
            query.setTargetId(root);
            assertEquals(0L, audit.pageContentOperations(query).getTotal());
        } finally {
            UserContext.clear();
            reset(writer);
            transaction.executeWithoutResult(tx -> {
                jdbc.update("DELETE FROM admin_operation_log WHERE module='content' AND target_type='comment' AND target_id=? AND admin_user_id=?",
                        Long.toString(root), fixture.userId());
                jdbc.update("DELETE FROM comment WHERE id=? AND game_id=? AND user_id=?", root, fixture.gameId(), fixture.userId());
                jdbc.update("DELETE FROM game WHERE id=? AND publisher_id=?", fixture.gameId(), fixture.userId());
                jdbc.update("DELETE FROM user WHERE id=? AND password='isolated-no-login'", fixture.userId());
            });
        }
    }

    private Fixture fixture() {
        String marker = "cv" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO user(username,password,role) VALUES (?, 'isolated-no-login', 0)", marker);
        Long userId = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, marker);
        jdbc.update("INSERT INTO game(name,publisher_id,status) VALUES (?,?,1)", marker, userId);
        Long gameId = jdbc.queryForObject("SELECT id FROM game WHERE name=? AND publisher_id=?", Long.class, marker, userId);
        return new Fixture(userId, gameId);
    }

    private Long comment(Fixture fixture, Long parent, Long replyTo, int status) {
        Comment comment = new Comment().setUserId(fixture.userId()).setGameId(fixture.gameId())
                .setParentId(parent).setReplyCommentId(replyTo).setStatus(status).setContent("评论可见性回归");
        assertEquals(1, comments.insert(comment));
        return comment.getId();
    }

    private record Fixture(Long userId, Long gameId) {}
    private record ModeratedComment(Fixture fixture, Long id) {}

    @Configuration
    static class ModerationConfig {
        @Bean IAdminOperationLogService audit(UserMapper users, PlatformTransactionManager transactionManager) {
            return spy(new AdminOperationLogServiceImpl(new ObjectMapper(), new UserAccountStatusServiceImpl(users), transactionManager));
        }

        @Bean IAdminContentService moderation(CommentMapper comments, GameReviewMapper reviews, GameMapper games,
                                              UserMapper users, IAdminOperationLogService audit) {
            // 此组验证 MySQL 的事务，Redis 增量前后的调用顺序由定向单测覆盖。
            var stats = mock(IGameStatsService.class);
            when(stats.getGameReviewCount(anyLong())).thenReturn(1);
            return new AdminContentServiceImpl(comments, reviews, games, users, stats, mock(RedisCounterService.class), audit);
        }
    }
}
