package com.catgal.server.service.impl;

import com.catgal.common.context.UserContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.catgal.common.context.UserInfo;
import com.catgal.common.domain.dto.ReplyCommentDTO;
import com.catgal.common.domain.query.PageQuery;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.Comment;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.CommentMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;

class CommentReplyTest {
    private final CommentMapper mapper = mock(CommentMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final IGameService games = mock(IGameService.class);
    private final GameMapper gameMapper = mock(GameMapper.class);
    private final CommentServiceImpl service = new CommentServiceImpl(users, games,
            mock(ILikeRecordService.class), gameMapper);

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Comment.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        when(games.getById(5L)).thenReturn(new Game().setId(5L).setStatus(1));
        when(gameMapper.selectForUpdate(5L)).thenReturn(new Game().setId(5L).setStatus(1));
    }
    @AfterEach void clear() { UserContext.clear(); }
    private Comment comment(long id, Long parent) {
        Comment result = new Comment(); result.setId(id); result.setParentId(parent); result.setGameId(5L);
        result.setUserId(9L); result.setStatus(1); return result;
    }
    private ReplyCommentDTO dto() {
        var dto = new ReplyCommentDTO(); dto.setGameId(5L); dto.setParentId(1L); dto.setReplyCommentId(2L);
        dto.setReplyUserId(999L); dto.setContent(" 回复内容 "); return dto;
    }
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deletingIntermediateReplyKeepsItsChildrenReachableFromRoot() {
        Comment child = comment(2L, 1L);
        child.setUserId(6L);
        CommentServiceImpl deleting = spy(service);
        doReturn(child).when(deleting).getById(2L);
        doReturn(true).when(deleting).removeById(child);
        when(mapper.selectForUpdate(1L)).thenReturn(comment(1L, null));
        when(mapper.selectForUpdate(2L)).thenReturn(child);
        deleting.deleteComment(2L);
        ArgumentCaptor<LambdaUpdateWrapper<Comment>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), update.capture());
        assertTrue(update.getValue().getSqlSet().contains("reply_comment_id="));
        assertTrue(update.getValue().getCustomSqlSegment().contains("parent_id"));
        assertTrue(update.getValue().getCustomSqlSegment().contains("reply_comment_id"));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(1L));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(2L));
        verify(gameMapper).refreshCommentCount(5L);
    }

    @Test
    void rejectsReplyTargetFromAnotherThread() {
        when(mapper.selectForUpdate(1L)).thenReturn(comment(1L, null));
        when(mapper.selectForUpdate(2L)).thenReturn(comment(2L, 99L));
        assertEquals(404, assertThrows(BusinessException.class, () -> service.replyComment(dto())).getHttpStatus());
        verify(mapper, never()).insert(any(Comment.class));
        verify(gameMapper, never()).refreshCommentCount(anyLong());
    }
    @Test
    void derivesReplyOwnerFromDatabaseAndTrimsText() {
        when(mapper.selectForUpdate(1L)).thenReturn(comment(1L, null));
        when(mapper.selectForUpdate(2L)).thenReturn(comment(2L, 1L));
        when(mapper.insert(any(Comment.class))).thenReturn(1);
        service.replyComment(dto());
        var saved = ArgumentCaptor.forClass(Comment.class); verify(mapper).insert(saved.capture());
        assertEquals(9L, saved.getValue().getReplyUserId());
        assertEquals("回复内容", saved.getValue().getContent());
        assertEquals(1, saved.getValue().getStatus());
        var writes = inOrder(gameMapper, mapper);
        writes.verify(gameMapper).selectForUpdate(5L);
        writes.verify(mapper).selectForUpdate(1L);
        writes.verify(mapper).insert(any(Comment.class));
        writes.verify(gameMapper).refreshCommentCount(5L);
    }

    @Test
    void flatThreadUsesRootAndVisibilityFiltersAndResolvesReplyRecipient() {
        when(mapper.selectById(1L)).thenReturn(comment(1L, null));
        Comment nestedReply = comment(3L, 1L).setReplyCommentId(2L).setReplyUserId(8L);
        User recipient = new User();
        recipient.setId(8L);
        recipient.setUsername("被回复的玩家");
        when(users.selectBatchIds(any())).thenReturn(List.of(recipient));
        doAnswer(invocation -> {
            Page<Comment> page = invocation.getArgument(0);
            LambdaQueryWrapper<Comment> query = invocation.getArgument(1);
            String filters = query.getCustomSqlSegment();
            assertTrue(filters.contains("game_id"));
            assertTrue(filters.contains("parent_id"));
            assertTrue(filters.contains("status"));
            assertFalse(filters.contains("reply_comment_id"));
            assertEquals(List.of("create_time", "id"), page.orders().stream().map(order -> order.getColumn()).toList());
            assertTrue(page.orders().stream().noneMatch(order -> order.isAsc()));
            return page.setTotal(1).setRecords(List.of(nestedReply));
        }).when(mapper).selectPage(any(), any());

        var page = service.getThreadReplies(1L, new PageQuery());
        assertEquals(1L, page.getTotal());
        assertEquals(2L, page.getList().get(0).getReplyCommentId());
        assertEquals("被回复的玩家", page.getList().get(0).getReplyUsername());
    }

    @Test
    void flatThreadRejectsHiddenRootsAndRepliesAsEntryPoints() {
        when(mapper.selectById(1L)).thenReturn(comment(1L, null).setStatus(0));
        when(mapper.selectById(2L)).thenReturn(comment(2L, 1L));
        assertEquals(404, assertThrows(BusinessException.class, () -> service.getThreadReplies(1L, new PageQuery())).getHttpStatus());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.getThreadReplies(2L, new PageQuery())).getHttpStatus());
        verify(mapper, never()).selectPage(any(), any());
    }
}
