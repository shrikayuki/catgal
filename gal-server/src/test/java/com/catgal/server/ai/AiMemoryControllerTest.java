package com.catgal.server.ai;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.controller.AiMemoryController;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mvc.LoginRequiredInterceptor;
import com.catgal.server.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

class AiMemoryControllerTest {
    private final AiServiceClient client = mock(AiServiceClient.class);
    private final AiServiceProperties properties = new AiServiceProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(validatedController())
            .addInterceptors(new LoginRequiredInterceptor(new CurrentUserProvider()))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    private Object validatedController() {
        ProxyFactory proxy = new ProxyFactory(new AiMemoryController(client, properties));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new MethodValidationInterceptor());
        return proxy.getProxy();
    }

    @AfterEach void clear() { UserContext.clear(); }
    private void login() {
        UserContext.setUser(UserInfo.builder().userId(7L).build());
        properties.setLongTermMemoryEnabled(true);
    }

    @Test void settingsAndMemoryListRequireLogin() throws Exception {
        mvc.perform(get("/ai/bots/12/memory-settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ai/bots/12/memories")).andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }

    @Test void featureSwitchDefaultsToDisabled() throws Exception {
        login();
        properties.setLongTermMemoryEnabled(false);
        mvc.perform(get("/ai/bots/12/memory-settings")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/ai/bots/12/memories")).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(client);
    }

    @Test void createUsesLoginAndPathIdentityIgnoringSuppliedUserOrBot() throws Exception {
        login();
        when(client.persistenceRequest(eq("memory/create"), anyMap())).thenReturn(
                mapper.readTree("{\"id\":\"34\",\"botId\":\"12\",\"revision\":\"1\"}"));
        mvc.perform(post("/ai/bots/12/memories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"喜欢治愈作品\",\"memoryType\":\"preference\",\"sourceMessageId\":8,\"userId\":99,\"botId\":88}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("34"));
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(client).persistenceRequest(eq("memory/create"), data.capture());
        assertEquals(7L, data.getValue().get("userId"));
        assertEquals(12L, data.getValue().get("botId"));
        assertEquals(8L, data.getValue().get("sourceMessageId"));
    }

    @Test void settingsRevisionAndFalseValueAreForwarded() throws Exception {
        login();
        when(client.persistenceRequest(eq("memory/settings/update"), anyMap())).thenReturn(
                mapper.readTree("{\"botId\":\"12\",\"memoryEnabled\":false,\"revision\":\"2\"}"));
        mvc.perform(put("/ai/bots/12/memory-settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1,\"memoryEnabled\":false,\"userId\":99}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.memoryEnabled").value(false));
        verify(client).persistenceRequest("memory/settings/update", Map.of(
                "userId", 7L, "botId", 12L, "revision", 1L, "memoryEnabled", false));
    }

    @Test void invalidSettingsAndMemoryFieldsFailBeforeProxy() throws Exception {
        login();
        mvc.perform(put("/ai/bots/12/memory-settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1}"))
                .andExpect(status().isBadRequest());
        for (String content : new String[]{
                "{\"content\":\" \",\"memoryType\":\"fact\"}",
                "{\"content\":\"value\",\"memoryType\":\"unsupported\"}",
                "{\"content\":\"value\",\"memoryType\":\"fact\",\"sourceMessageId\":0}",
                "{\"content\":\"" + "x".repeat(1001) + "\",\"memoryType\":\"fact\"}"
        }) {
            mvc.perform(post("/ai/bots/12/memories").contentType(MediaType.APPLICATION_JSON).content(content))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(client);
    }

    @Test void createAndUpdateAcceptOneThousandSupplementaryPlaneCodePoints() throws Exception {
        login();
        String content = new String(Character.toChars(0x1F600)).repeat(1000);
        assertEquals(2000, content.length());
        mvc.perform(post("/ai/bots/12/memories").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("content", content, "memoryType", "fact"))))
                .andExpect(status().isOk());
        mvc.perform(put("/ai/bots/12/memories/34").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("revision", 1, "content", content, "memoryType", "fact"))))
                .andExpect(status().isOk());
        verify(client).persistenceRequest("memory/create", Map.of(
                "userId", 7L, "botId", 12L, "content", content, "memoryType", "fact"));
        verify(client).persistenceRequest("memory/update", Map.of(
                "userId", 7L, "botId", 12L, "memoryId", 34L, "revision", 1L, "content", content, "memoryType", "fact"));
    }

    @Test void createAndUpdateRejectOneThousandAndOneSupplementaryPlaneCodePoints() throws Exception {
        login();
        String content = new String(Character.toChars(0x1F600)).repeat(1001);
        mvc.perform(post("/ai/bots/12/memories").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("content", content, "memoryType", "fact"))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/ai/bots/12/memories/34").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("revision", 1, "content", content, "memoryType", "fact"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test void listForwardsOnlyUserScopedCursorAndLimit() throws Exception {
        login();
        mvc.perform(get("/ai/bots/12/memories").param("beforeId", "88").param("limit", "30")
                        .param("userId", "99"))
                .andExpect(status().isOk());
        verify(client).persistenceRequest("memory/list", Map.of(
                "userId", 7L, "botId", 12L, "beforeId", 88L, "limit", 30));
    }

    @Test void cursorLimitsPathIdsAndDeleteRevisionAreValidated() throws Exception {
        login();
        mvc.perform(get("/ai/bots/12/memories").param("limit", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/ai/bots/12/memories").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/ai/bots/12/memories").param("beforeId", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/ai/bots/0/memory-settings")).andExpect(status().isBadRequest());
        mvc.perform(delete("/ai/bots/12/memories/0").param("revision", "1")).andExpect(status().isBadRequest());
        mvc.perform(delete("/ai/bots/12/memories/34").param("revision", "0")).andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test void updateAndDeleteUsePathMemoryIdAndRevision() throws Exception {
        login();
        mvc.perform(put("/ai/bots/12/memories/34").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":2,\"content\":\"new\",\"memoryType\":\"fact\",\"memoryId\":99}"))
                .andExpect(status().isOk());
        verify(client).persistenceRequest("memory/update", Map.of(
                "userId", 7L, "botId", 12L, "memoryId", 34L, "revision", 2L, "content", "new", "memoryType", "fact"));
        mvc.perform(delete("/ai/bots/12/memories/34").param("revision", "3"))
                .andExpect(status().isOk());
        verify(client).persistenceRequest("memory/delete", Map.of(
                "userId", 7L, "botId", 12L, "memoryId", 34L, "revision", 3L));
    }

    @Test void backendOwnershipAndRevisionFailuresKeepHttpStatus() throws Exception {
        login();
        when(client.persistenceRequest("memory/settings/get", Map.of("userId", 7L, "botId", 12L)))
                .thenThrow(BusinessException.notFound("机器人不存在"));
        mvc.perform(get("/ai/bots/12/memory-settings")).andExpect(status().isNotFound());
        when(client.persistenceRequest(eq("memory/delete"), anyMap()))
                .thenThrow(BusinessException.conflict("配置已更新"));
        mvc.perform(delete("/ai/bots/12/memories/34").param("revision", "1"))
                .andExpect(status().isConflict());
    }

    @Test void memoryContentIsRedactedFromDtoToString() {
        var create = new AiMemoryController.CreateMemory("private memory", "fact", null);
        var update = new AiMemoryController.UpdateMemory(1L, "private memory", "preference");
        assertFalse(create.toString().contains("private memory"));
        assertFalse(update.toString().contains("private memory"));
    }
}
