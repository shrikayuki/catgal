package com.catgal.server.ai;

import com.catgal.common.domain.dto.PageDTO;
import com.catgal.common.domain.vo.GameVO;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.ai.tools.AiCatalogToolService;
import com.catgal.server.ai.tools.AiInterestReader;
import com.catgal.server.ai.tools.AiToolRequests;
import com.catgal.server.catalog.GameRelationsService;
import com.catgal.server.domain.po.Game;
import com.catgal.server.domain.po.GameReview;
import com.catgal.server.service.IGameService;
import com.catgal.server.search.MysqlGameSearch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.catgal.server.service.IUserAccountStatusService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiCatalogToolServiceTest {
    private final IGameService games = mock(IGameService.class);
    private final GameRelationsService relations = mock(GameRelationsService.class);
    private final AiInterestReader interests = mock(AiInterestReader.class);
    private final IUserAccountStatusService accountStatus = mock(IUserAccountStatusService.class);
    private final MysqlGameSearch mysqlSearch = mock(MysqlGameSearch.class);
    private final AiCatalogToolService service = new AiCatalogToolService(games, relations, interests, accountStatus, mysqlSearch);


    private Game game(long id, int status) {
        return new Game().setId(id).setStatus(status).setName("Game " + id).setIntroduction("text ".repeat(500));
    }

    @Test void unpublishedOrRemovedSearchHitsAreExcludedAndPayloadIsBounded() {
        GameVO first = new GameVO(); first.setId(1L);
        GameVO removed = new GameVO(); removed.setId(2L);
        when(games.pageGames(any())).thenReturn(new PageDTO<>(20L, 4L, List.of(first, removed)));
        when(games.listByIds(List.of(1L, 2L))).thenReturn(List.of(game(1, 1), game(2, 2)));
        var result = service.search(new AiToolRequests.Search(7L, "test", 1, 2L, 2020, "中文", "win", null, 5));
        assertEquals(List.of("1"), result.items().stream().map(value -> value.id()).toList());
        assertTrue(result.hasMore());
        assertEquals(1000, result.items().get(0).introduction().length());
        verify(games).pageGames(argThat(query -> query.getPageSize() == 5 && query.getPageNo() == 1 && query.getTagId() == 1));
        verify(games, never()).getGameById(any());
    }

    @Test void detailDoesNotIncrementViewsAndOnlyPublishedGamesAreReadable() {
        when(games.listByIds(List.of(1L))).thenReturn(List.of(game(1, 0)));
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.detail(new AiToolRequests.Detail(7L, 1L))).getHttpStatus());
        verify(games, never()).getGameById(any());
        verifyNoInteractions(relations);
    }

    @Test void bannedUserCannotReadInterests() {
        doThrow(new BusinessException(40301, 403, "账号已被封禁")).when(accountStatus).requireActiveUser(7L);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.interests(new AiToolRequests.Interests(7L, 5))).getHttpStatus());
        verifyNoInteractions(games, interests);
    }

    @Test void emptyInterestsUseNoUnboundedGameQuery() {
        when(interests.read(7, 5)).thenReturn(new AiInterestReader.Snapshot(List.of(), List.of(), false, "current"));
        var result = service.interests(new AiToolRequests.Interests(7L, 5));
        assertTrue(result.favorites().isEmpty());
        verifyNoInteractions(games, relations);
    }

    @Test void publishedInterestProjectionContainsOnlyStructuredReviewSignals() {
        GameReview review = new GameReview().setGameId(2L).setScore(9).setRecommendLevel(5).setPlayStatus(5)
                .setBriefReview("private body must not appear");
        when(interests.read(7, 1)).thenReturn(new AiInterestReader.Snapshot(List.of(1L, 3L), List.of(review), true, "database_fallback"));
        when(games.listByIds(anyCollection())).thenReturn(List.of(game(1, 1), game(2, 1), game(3, 2)));
        var result = service.interests(new AiToolRequests.Interests(7L, 1));
        assertEquals("1", result.favorites().get(0).id());
        assertEquals(9, result.reviews().get(0).score());
        assertTrue(result.sampled());
        assertEquals("database_fallback", result.sourceStatus());
        assertFalse(result.toString().contains("private body"));
    }

    @Test void validatesEveryCandidateBeforeApplyingLimitAndPreservesSemanticRank() {
        when(mysqlSearch.filterCurrentCandidates(anyMap(), any())).thenReturn(List.of(2L, 3L));
        when(games.listByIds(List.of(3L, 2L))).thenReturn(List.of(game(2, 1), game(3, 1)));
        var result = service.validate(new AiToolRequests.Validate(7L, List.of(new AiToolRequests.Candidate(1L, 8L),
                new AiToolRequests.Candidate(3L, 4L), new AiToolRequests.Candidate(2L, 2L)), 10, 20L,
                2020, "中文", "win", "汉化", 2));
        assertEquals(List.of("3", "2"), result.items().stream().map(value -> value.id()).toList());
        verify(mysqlSearch).filterCurrentCandidates(eq(Map.of(1L, 8L, 3L, 4L, 2L, 2L)),
                argThat(query -> query.getKeyword() == null && query.getCompanyId() == 20L
                        && query.getTagId() == 10 && "win".equals(query.getPlatform()) && query.getReleaseYear() == 2020));
        verify(games, never()).pageGames(any());
    }

    @Test void contradictoryCandidateVersionsAreRejectedAndInactiveActorCannotValidate() {
        var request = new AiToolRequests.Validate(7L, List.of(new AiToolRequests.Candidate(1L, 1L),
                new AiToolRequests.Candidate(1L, 2L)), null, null, null, null, null, null, 2);
        assertEquals(400, assertThrows(BusinessException.class, () -> service.validate(request)).getHttpStatus());
        doThrow(new BusinessException(40301, 403, "账号已被封禁")).when(accountStatus).requireActiveUser(7L);
        assertEquals(403, assertThrows(BusinessException.class, () -> service.validate(request)).getHttpStatus());
        verifyNoInteractions(mysqlSearch, games);
    }
}
