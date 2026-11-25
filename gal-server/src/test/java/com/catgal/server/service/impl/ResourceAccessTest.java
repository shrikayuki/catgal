package com.catgal.server.service.impl;

import com.catgal.common.exception.BusinessException;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.domain.dto.ResourceAddDTO;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.Resource;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.ResourceMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.ResourceReviewHistoryMapper;
import com.catgal.server.domain.po.ResourceReviewHistory;
import com.catgal.server.service.IGameService;
import com.catgal.server.service.IGameStatsService;
import com.catgal.server.service.ILikeRecordService;
import com.catgal.server.service.RedisCounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceAccessTest {
    @Test
    void publicationRejectsUnsafeAndMalformedLinksBeforeWriting() {
        for (String url : new String[]{null, "", "javascript:alert(1)", "file:///tmp/file", "https://user:secret@example.com/file", "//example.com/file", "https://example.com/\nfile"}) {
            ResourceAddDTO dto = new ResourceAddDTO();
            dto.setDownloadUrl(url);
            assertEquals(400, assertThrows(BusinessException.class, () -> service.addResource(dto)).getHttpStatus());
        }
        verifyNoInteractions(mapper, games, stats, counters);
    }
    private final ResourceMapper mapper = mock(ResourceMapper.class);
    private final IGameService games = mock(IGameService.class);
    private final IGameStatsService stats = mock(IGameStatsService.class);
    private final RedisCounterService counters = mock(RedisCounterService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final ILikeRecordService likes = mock(ILikeRecordService.class);
    private final GameMapper gameMapper = mock(GameMapper.class);
    private final ResourceReviewHistoryMapper history = mock(ResourceReviewHistoryMapper.class);
    private final ResourceServiceImpl service = new ResourceServiceImpl(users, games, likes, stats, counters,
            gameMapper, history, new UserAccountStatusServiceImpl(users));

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), Resource.class);
        when(history.insert(any(ResourceReviewHistory.class))).thenReturn(1);
    }

    @AfterEach void clearUser() { UserContext.clear(); }

    private void administrator() {
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        User user = new User();
        user.setId(6L);
        user.setRole(2);
        user.setStatus(1);
        when(users.selectForUpdate(6L)).thenReturn(user);
        when(gameMapper.selectForUpdate(5L)).thenReturn(new Game().setId(5L).setStatus(1));
    }

    private void resource(int status) {
        Resource resource = new Resource();
        resource.setId(10L);
        resource.setGameId(5L);
        resource.setUserId(6L);
        resource.setStatus(status);
        resource.setDownloadUrl("https://example.com/resource");
        resource.setExtractCode("test");
        resource.setRevision(0L);
        when(mapper.selectById(10L)).thenReturn(resource);
        when(mapper.selectForUpdate(10L)).thenReturn(resource);
    }

    @Test
    void publishedResourceOfPublishedGameCanBeRead() {
        resource(1);
        when(games.getById(5L)).thenReturn(new Game().setId(5L).setStatus(1));
        when(stats.getGameDownLoadCount(5L)).thenReturn(8);
        when(stats.getResourceDownLoadCount(10L)).thenReturn(3);
        assertEquals("test", service.getResourceUrl(10L).getExtractCode());
        assertEquals("https://example.com/resource", service.download(10L));
        verify(counters).changeAndMark("game:download:count:5", 8L, 1L, "game:stats:count:change", 5L);
        verify(counters).changeAndMark("resource:download:count:10", 3L, 1L, "resource:download:count:change", 10L);
        verifyNoMoreInteractions(counters);
    }

    @Test
    void draftAndWithdrawnGamesCannotExposeLinksOrCountDownloads() {
        resource(1);
        for (int status : new int[]{0, 2}) {
            when(games.getById(5L)).thenReturn(new Game().setId(5L).setStatus(status));
            assertEquals(404, assertThrows(BusinessException.class, () -> service.getResourceUrl(10L)).getHttpStatus());
            assertEquals(404, assertThrows(BusinessException.class, () -> service.download(10L)).getHttpStatus());
        }
        verifyNoInteractions(stats, counters);
    }

    @Test
    void missingGameAndInactiveResourceCannotExposeLinks() {
        resource(1);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.getResourceUrl(10L)).getHttpStatus());
        resource(2);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.download(10L)).getHttpStatus());
        verifyNoInteractions(stats, counters);
    }

    @Test
    void invalidIdsAreRejectedBeforeDatabaseAccess() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.download(-1L)).getHttpStatus());
        verifyNoInteractions(mapper, games, stats, counters);
    }

    @Test void physicalResourceDeletionExplicitlyClearsLikeRelationships() {
        resource(2);
        administrator();
        when(mapper.deleteById(any(java.io.Serializable.class))).thenReturn(1);
        service.deleteResource(10L);
        verify(likes).clearLikeCache("resource", 10L);
    }

    @Test void aFailedResourceDeletionDoesNotClearLikeRelationships() {
        resource(2);
        administrator();
        assertEquals(409, assertThrows(BusinessException.class, () -> service.deleteResource(10L)).getHttpStatus());
        verifyNoInteractions(likes, counters);
    }
}
