package com.catgal.server.ai;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.controller.AiBotController;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mvc.LoginRequiredInterceptor;
import com.catgal.server.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiBotControllerTest {
    private final AiServiceClient client = mock(AiServiceClient.class);
    private final AiServiceProperties properties = new AiServiceProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiBotController(client, properties))
            .addInterceptors(new LoginRequiredInterceptor(new CurrentUserProvider()))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach void clear() { UserContext.clear(); }
    private void login() {
        UserContext.setUser(UserInfo.builder().userId(7L).build());
        properties.setPersonalizationEnabled(true);
    }

    @Test void anonymousUsersCannotReadBotsOrCredentials() throws Exception {
        mvc.perform(get("/ai/bots")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ai/credentials")).andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }

    @Test void featureIsDisabledUntilConfigured() throws Exception {
        login();
        properties.setPersonalizationEnabled(false);
        mvc.perform(get("/ai/templates")).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(client);
    }

    @Test void credentialCreationUsesAuthenticatedUserAndReturnsOnlySummary() throws Exception {
        login();
        when(client.persistenceRequest(eq("credentials/create"), anyMap())).thenReturn(
                mapper.readTree("{\"id\":\"1\",\"maskedKey\":\"****1234\",\"revision\":\"1\"}"));
        String result = mvc.perform(post("/ai/credentials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerCode\":\"dashscope\",\"label\":\"my-key\",\"apiKey\":\"qa-user-key-1234\",\"userId\":99}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.maskedKey").value("****1234"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(result.contains("qa-user-key"));
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).persistenceRequest(eq("credentials/create"), payload.capture());
        assertEquals(7L, payload.getValue().get("userId"));
        assertEquals("qa-user-key-1234", payload.getValue().get("apiKey"));
    }

    @Test void secretDtoIsWriteOnlyAndRedactedInToString() throws Exception {
        var body = new AiBotController.CredentialCreate("dashscope", "QA", " qa-user-key-1234 ");
        assertEquals("qa-user-key-1234", body.apiKey());
        assertFalse(mapper.writeValueAsString(body).contains("qa-user-key"));
        assertFalse(body.toString().contains("qa-user-key"));
        var rotate = new AiBotController.CredentialRotate(1L, "qa-user-key-5678");
        assertFalse(mapper.writeValueAsString(rotate).contains("qa-user-key"));
        assertFalse(rotate.toString().contains("qa-user-key"));
    }

    @Test void invalidSecretIsRejectedWithoutEchoingValue() throws Exception {
        login();
        String result = mvc.perform(post("/ai/credentials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerCode\":\"dashscope\",\"label\":\"QA\",\"apiKey\":\"sensitive key with spaces\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertFalse(result.contains("sensitive key"));
        verifyNoInteractions(client);
    }

    @Test void unownedBotIsMappedToNotFound() throws Exception {
        login();
        when(client.persistenceRequest("bots/get", Map.of("userId", 7L, "botId", 99L)))
                .thenThrow(BusinessException.notFound("机器人不存在"));
        mvc.perform(get("/ai/bots/99")).andExpect(status().isNotFound());
    }
}
