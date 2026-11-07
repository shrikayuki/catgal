package com.catgal.server.service.impl;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.config.repository.ChatHistoryRepository;
import com.catgal.server.domain.po.AiGirl;
import com.catgal.server.mapper.UserSessionMapper;
import com.catgal.server.task.AiGirlCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiGirlServiceImplTest {

    private final AiGirlCacheService cacheService = mock(AiGirlCacheService.class);
    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatHistoryRepository chatHistoryRepository = mock(ChatHistoryRepository.class);
    private final UserSessionMapper userSessionMapper = mock(UserSessionMapper.class);
    private final AiGirlServiceImpl service = new AiGirlServiceImpl(
            cacheService,
            chatClient,
            chatHistoryRepository,
            userSessionMapper
    );

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
        verifyNoInteractions(chatClient);
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
        verifyNoInteractions(chatHistoryRepository, userSessionMapper, chatClient);
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
