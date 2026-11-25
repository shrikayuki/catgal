package com.catgal.server.ai;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.controller.AiMemoryVectorController;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mvc.LoginRequiredInterceptor;
import com.catgal.server.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiMemoryVectorControllerTest {
    private static final String PATH = "/ai/bots/12/memory-vector-settings";
    private final AiServiceClient client = mock(AiServiceClient.class);
    private final AiServiceProperties properties = new AiServiceProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(validatedController())
            .addInterceptors(new LoginRequiredInterceptor(new CurrentUserProvider()))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    private Object validatedController() {
        ProxyFactory proxy = new ProxyFactory(new AiMemoryVectorController(client, properties));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new MethodValidationInterceptor());
        return proxy.getProxy();
    }

    @AfterEach void clear() { UserContext.clear(); }

    private void login() {
        UserContext.setUser(UserInfo.builder().userId(7L).build());
        properties.setMemoryVectorEnabled(true);
    }

    @Test void bothOperationsRequireLogin() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0,\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }

    @Test void featureDefaultsOffAndDoesNotInvokePython() throws Exception {
        assertFalse(properties.isMemoryVectorEnabled());
        UserContext.setUser(UserInfo.builder().userId(7L).build());
        mvc.perform(get(PATH)).andExpect(status().isServiceUnavailable());
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0,\"enabled\":true}"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(client);
    }

    @Test void settingsInjectActorAndUsePathBotAndPreserveInitialStringRevision() throws Exception {
        login();
        when(client.persistenceRequest("memory-vector/settings/get", Map.of("userId", 7L, "botId", 12L)))
                .thenReturn(mapper.readTree("{\"botId\":\"12\",\"enabled\":false,\"revision\":\"0\"}"));
        mvc.perform(get(PATH).param("userId", "99").param("botId", "88"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.botId").value("12"))
                .andExpect(jsonPath("$.data.enabled").value(false)).andExpect(jsonPath("$.data.revision").value("0"));
        verify(client).persistenceRequest("memory-vector/settings/get", Map.of("userId", 7L, "botId", 12L));
    }

    @Test void zeroRevisionIsValidAndForgedIdentityCannotChangePayload() throws Exception {
        login();
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"enabled\":true,\"userId\":99,\"botId\":88}"))
                .andExpect(status().isOk());
        verify(client).persistenceRequest("memory-vector/settings/update", Map.of(
                "userId", 7L, "botId", 12L, "revision", 0L, "enabled", true));
    }

    @Test void disablingIsExplicitlyForwardedWithCurrentRevision() throws Exception {
        login();
        when(client.persistenceRequest(eq("memory-vector/settings/update"), anyMap())).thenReturn(
                mapper.readTree("{\"botId\":\"12\",\"enabled\":false,\"revision\":\"3\"}"));
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2,\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        verify(client).persistenceRequest("memory-vector/settings/update", Map.of(
                "userId", 7L, "botId", 12L, "revision", 2L, "enabled", false));
    }

    @Test void revisionRequiredBooleanAndPathBoundsAreValidated() throws Exception {
        login();
        for (String body : new String[]{"{\"revision\":-1,\"enabled\":true}", "{\"revision\":0}",
                "{\"enabled\":true}", "{\"revision\":null,\"enabled\":true}", "{\"revision\":0,\"enabled\":null}"}) {
            mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/ai/bots/0/memory-vector-settings")).andExpect(status().isBadRequest());
        mvc.perform(put("/ai/bots/-1/memory-vector-settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test void ownershipConflictAndUnavailableStatusesArePreserved() throws Exception {
        login();
        when(client.persistenceRequest(eq("memory-vector/settings/get"), anyMap()))
                .thenThrow(BusinessException.notFound("机器人不存在"));
        mvc.perform(get(PATH)).andExpect(status().isNotFound());
        when(client.persistenceRequest(eq("memory-vector/settings/update"), anyMap()))
                .thenThrow(BusinessException.conflict("配置已更新"));
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1,\"enabled\":true}"))
                .andExpect(status().isConflict());
        when(client.persistenceRequest(eq("memory-vector/settings/get"), anyMap()))
                .thenThrow(new BusinessException(503, 503, "记忆向量检索功能暂不可用"));
        mvc.perform(get(PATH)).andExpect(status().isServiceUnavailable());
    }

    @Test void settingsDtoStringIsRedacted() {
        assertEquals("UpdateSettings[redacted]", new AiMemoryVectorController.UpdateSettings(0L, true).toString());
    }
}
