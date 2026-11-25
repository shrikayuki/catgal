package com.catgal.server.service.impl;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.ai.AiChatCommand;
import com.catgal.server.ai.AiServiceClient;
import com.catgal.server.ai.AiServiceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.catgal.server.config.repository.ChatHistoryRepository;
import com.catgal.server.domain.po.AiGirl;
import com.catgal.server.mapper.UserSessionMapper;
import com.catgal.server.task.AiGirlCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;

class AiGirlServiceImplTest {
    @Test
    void structuredStreamUsesTheSameOwnershipBoundary() {
        loginAs(7L);
        when(chatHistoryRepository.getHistorySessionIds("emily", 7L)).thenReturn(List.of());
        when(userSessionMapper.selectByUserAndGirlCode(7L, "emily")).thenReturn(List.of());
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.streamChat("emily", "other-session", "你好"));
        assertEquals(404, exception.getHttpStatus());
        verifyNoInteractions(aiServiceClient);
    }

    private final AiGirlCacheService cacheService = mock(AiGirlCacheService.class);
    private final AiServiceClient aiServiceClient = mock(AiServiceClient.class);
    private final ChatHistoryRepository chatHistoryRepository = mock(ChatHistoryRepository.class);
    private final UserSessionMapper userSessionMapper = mock(UserSessionMapper.class);
    private final AiServiceProperties properties = new AiServiceProperties();
    private final AiGirlServiceImpl service = new AiGirlServiceImpl(
            cacheService,
            aiServiceClient,
            chatHistoryRepository,
            userSessionMapper,
            properties
    );

    @Test
    void persistenceModeDelegatesSessionCreationAndChatWithoutLegacyRedisWrites() throws Exception {
        loginAs(7L);
        properties.setPersistenceEnabled(true);
        when(aiServiceClient.persistenceRequest(eq("sessions/create"), anyMap()))
                .thenReturn(new ObjectMapper().readTree("{\"sessionId\":\"s1\"}"));
        assertEquals("s1", service.newSession("emily"));
        when(aiServiceClient.persistentChat(anyMap())).thenReturn(Flux.empty());
        service.streamChat("emily", "s1", "hello").blockLast();
        verify(aiServiceClient).persistenceRequest("sessions/get",
                Map.of("userId", 7L, "girlCode", "emily", "sessionId", "s1"));
        verifyNoInteractions(cacheService, chatHistoryRepository, userSessionMapper);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void newSessionRequiresLogin() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.newSession("emily"));

        assertEquals(401, exception.getHttpStatus());
        verifyNoInteractions(cacheService, chatHistoryRepository);
    }

    @Test
    void newSessionRejectsDisabledGirl() {
        loginAs(1L);
        when(cacheService.getByCode("emily")).thenReturn(girl(0));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.newSession("emily"));

        assertEquals(404, exception.getHttpStatus());
        verifyNoInteractions(chatHistoryRepository);
    }

    @Test
    void newSessionStoresUserScopedHistory() {
        loginAs(7L);
        when(cacheService.getByCode("emily")).thenReturn(girl(1));

        String sessionId = service.newSession("emily");

        assertFalse(sessionId.isBlank());
        verify(chatHistoryRepository).save("emily", 7L, sessionId);
    }

    @Test
    void chatRejectsSessionNotOwnedByCurrentUserBeforeCallingModel() {
        loginAs(7L);
        when(chatHistoryRepository.getHistorySessionIds("emily", 7L)).thenReturn(List.of());
        when(userSessionMapper.selectByUserAndGirlCode(7L, "emily")).thenReturn(List.of());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.chatWithAIGirl("emily", "123456", "你好"));

        assertEquals(404, exception.getHttpStatus());
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void databaseHistoryIsRehydratedWithoutReversingNewestFirstOrder() {
        loginAs(7L);
        when(chatHistoryRepository.getHistorySessionIds("emily", 7L)).thenReturn(List.of());
        when(userSessionMapper.selectByUserAndGirlCode(7L, "emily"))
                .thenReturn(List.of("new-session", "old-session"));

        List<String> result = service.getHistorySessionIds("emily");

        assertEquals(List.of("new-session", "old-session"), result);
        var ordered = inOrder(chatHistoryRepository);
        ordered.verify(chatHistoryRepository).save("emily", 7L, "old-session");
        ordered.verify(chatHistoryRepository).save("emily", 7L, "new-session");
    }

    @Test
    void invalidSessionIdentifierIsRejectedBeforeRepositoryAccess() {
        loginAs(7L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.chatWithAIGirl("emily", "other:user:session", "你好"));

        assertEquals(400, exception.getHttpStatus());
        verifyNoInteractions(chatHistoryRepository, userSessionMapper, aiServiceClient);
    }

    @Test
    void chatDelegatesValidatedContextToPythonService() {
        loginAs(7L);
        when(chatHistoryRepository.getHistorySessionIds("emily", 7L))
                .thenReturn(List.of("123456"));
        when(cacheService.getByCode("emily")).thenReturn(girl(1));
        when(aiServiceClient.streamChat(org.mockito.ArgumentMatchers.any(AiChatCommand.class)))
                .thenReturn(Flux.just("你", "好"));

        List<String> result = service.chatWithAIGirl("emily", "123456", "你好")
                .collectList()
                .block();

        assertEquals(List.of("你", "好"), result);
        ArgumentCaptor<AiChatCommand> command = ArgumentCaptor.forClass(AiChatCommand.class);
        verify(aiServiceClient).streamChat(command.capture());
        assertEquals(7L, command.getValue().userId());
        assertEquals("emily", command.getValue().girlCode());
        assertEquals("123456", command.getValue().sessionId());
        assertEquals("你好", command.getValue().userMessage());
        assertEquals("你是艾米丽", command.getValue().systemPrompt());
    }

    private void loginAs(Long userId) {
        UserContext.setUser(UserInfo.builder().userId(userId).build());
    }

    private AiGirl girl(int status) {
        return new AiGirl()
                .setGirlCode("emily")
                .setStatus(status)
                .setSystemPrompt("你是艾米丽");
    }
}
