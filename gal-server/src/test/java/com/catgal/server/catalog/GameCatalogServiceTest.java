package com.catgal.server.catalog;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.dto.admin.GameCatalogCreateDTO;
import com.catgal.server.domain.dto.admin.GameCatalogUpdateDTO;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameCatalogServiceTest {
    private final GameMapper games = mock(GameMapper.class);
    private final GameRelationsService relations = mock(GameRelationsService.class);
    private final GamePublicationCache publication = mock(GamePublicationCache.class);
    private final GameCatalogService service = new GameCatalogService(games, relations, publication);

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Game.class);
        UserContext.setUser(UserInfo.builder().userId(6L).role(2).build());
    }

    @AfterEach
    void clear() { UserContext.clear(); }

    @Test
    void createUsesCurrentPublisherAndDraftState() {
        GameCatalogCreateDTO dto = new GameCatalogCreateDTO();
        dto.setName("  新游戏  ");
        when(games.insert(any(Game.class))).thenAnswer(invocation -> {
            Game game = invocation.getArgument(0);
            assertEquals(6L, game.getPublisherId());
            assertEquals(0, game.getStatus());
            assertEquals(0L, game.getRevision());
            assertEquals("新游戏", game.getName());
            game.setId(99L);
            return 1;
        });
        when(games.selectById(99L)).thenReturn(new Game().setId(99L).setStatus(0));
        assertEquals(99L, service.create(dto).game().getId());
        verify(relations).replaceAll(99L, dto.getCompanyIds(), List.of());
        verifyNoInteractions(publication);
    }

    @Test
    void staleRevisionCannotModifyGameOrRelations() {
        GameCatalogUpdateDTO dto = new GameCatalogUpdateDTO();
        dto.setName("新名称");
        dto.setRevision(2L);
        when(games.selectForUpdate(9L)).thenReturn(new Game().setId(9L).setRevision(3L));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.update(9L, dto)).getHttpStatus());
        verifyNoInteractions(relations, publication);
        verify(games, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateTouchesOnlyEditableFieldsAndRevision() {
        GameCatalogUpdateDTO dto = new GameCatalogUpdateDTO();
        dto.setName("新名称");
        dto.setRevision(2L);
        when(games.selectForUpdate(9L)).thenReturn(new Game().setId(9L).setRevision(2L));
        when(games.update(isNull(), any(Wrapper.class))).thenAnswer(invocation -> {
            LambdaUpdateWrapper<Game> wrapper = invocation.getArgument(1);
            String assignments = wrapper.getSqlSet();
            assertTrue(assignments.contains("cover_url="));
            assertTrue(assignments.contains("release_date="));
            assertTrue(assignments.contains("revision="));
            assertFalse(assignments.contains("rating="));
            assertFalse(assignments.contains("publisher_id="));
            assertFalse(assignments.contains("status="));
            assertFalse(assignments.contains("types="));
            return 1;
        });
        when(games.selectById(9L)).thenReturn(new Game().setId(9L).setRevision(3L));
        assertEquals(3L, service.update(9L, dto).game().getRevision());
    }

    @Test
    void draftRelationsAreHiddenFromPublicReads() {
        when(games.selectById(9L)).thenReturn(new Game().setId(9L).setStatus(0));
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.publicRelations(CatalogKind.COMPANY, 9L)).getHttpStatus());
        verifyNoInteractions(relations);
    }

    @Test
    void invalidCoverRejectedBeforeAnyWrite() {
        GameCatalogCreateDTO dto = new GameCatalogCreateDTO();
        dto.setName("游戏");
        dto.setCoverUrl("file:///etc/passwd");
        assertEquals(400, assertThrows(BusinessException.class, () -> service.create(dto)).getHttpStatus());
        verifyNoInteractions(games, relations);
    }

    @Test
    void anonymousCreateRejectedBeforeAnyWrite() {
        UserContext.clear();
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.create(new GameCatalogCreateDTO())).getHttpStatus());
        verifyNoInteractions(games, relations);
    }
}
