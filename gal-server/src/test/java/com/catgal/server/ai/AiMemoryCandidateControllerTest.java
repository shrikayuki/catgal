package com.catgal.server.ai;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.controller.AiMemoryCandidateController;
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

class AiMemoryCandidateControllerTest {
    private final AiServiceClient client = mock(AiServiceClient.class);
    private final AiServiceProperties properties = new AiServiceProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(validatedController())
            .addInterceptors(new LoginRequiredInterceptor(new CurrentUserProvider()))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    private Object validatedController() {
        ProxyFactory proxy = new ProxyFactory(new AiMemoryCandidateController(client, properties));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new MethodValidationInterceptor());
        return proxy.getProxy();
    }

    @AfterEach void clear() { UserContext.clear(); }
    private void login() {
        UserContext.setUser(UserInfo.builder().userId(7L).build());
        properties.setMemoryCandidatesEnabled(true);
    }

    @Test void candidateOperationsRequireLogin() throws Exception {
        mvc.perform(get("/ai/bots/12/memory-candidate-settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ai/bots/12/memory-candidates")).andExpect(status().isUnauthorized());
        mvc.perform(post("/ai/bots/12/memory-candidates/34/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1,\"content\":\"value\",\"memoryType\":\"fact\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/ai/bots/12/memory-candidates/34/reject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }

    @Test void featureDefaultsToDisabled() throws Exception {
        login();
        properties.setMemoryCandidatesEnabled(false);
        mvc.perform(get("/ai/bots/12/memory-candidate-settings")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/ai/bots/12/memory-candidates")).andExpect(status().isServiceUnavailable());
        mvc.perform(put("/ai/bots/12/memory-candidate-settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"enabled\":true}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/ai/bots/12/memory-candidates/34/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1,\"content\":\"value\",\"memoryType\":\"fact\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/ai/bots/12/memory-candidates/34/reject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1}"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(client);
    }

    @Test void settingsUseAuthenticatedUserAndAllowInitialZeroRevision() throws Exception {
        login();
        when(client.persistenceRequest("memory-candidates/settings/get", Map.of("userId", 7L, "botId", 12L)))
                .thenReturn(mapper.readTree("{\"botId\":\"12\",\"enabled\":false,\"revision\":\"0\"}"));
        mvc.perform(get("/ai/bots/12/memory-candidate-settings").param("userId", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value("0"));
        mvc.perform(put("/ai/bots/12/memory-candidate-settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"enabled\":true,\"userId\":99,\"botId\":88}"))
                .andExpect(status().isOk());
        verify(client).persistenceRequest("memory-candidates/settings/update", Map.of(
                "userId", 7L, "botId", 12L, "revision", 0L, "enabled", true));
    }

    @Test void listForwardsOwnedBotAndCursorAndReturnsExtractionStatus() throws Exception {
        login();
        when(client.persistenceRequest(eq("memory-candidates/list"), anyMap())).thenReturn(mapper.readTree(
                "{\"items\":[],\"hasMore\":false,\"nextCursor\":null,\"lastExtraction\":{\"status\":\"failed\",\"errorCode\":\"MODEL_UPSTREAM_ERROR\",\"updateTime\":null}}"));
        mvc.perform(get("/ai/bots/12/memory-candidates").param("beforeId", "88").param("limit", "30")
                        .param("userId", "99").param("botId", "90"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lastExtraction.status").value("failed"));
        verify(client).persistenceRequest("memory-candidates/list", Map.of(
                "userId", 7L, "botId", 12L, "beforeId", 88L, "limit", 30));
    }

    @Test void confirmUsesPathCandidateAndUserContextIgnoringForgedIds() throws Exception {
        login();
        when(client.persistenceRequest(eq("memory-candidates/confirm"), anyMap())).thenReturn(
                mapper.readTree("{\"memoryId\":\"56\",\"duplicate\":false}"));
        mvc.perform(post("/ai/bots/12/memory-candidates/34/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1,\"content\":\"喜欢治愈作品\",\"memoryType\":\"preference\",\"userId\":99,\"botId\":88,\"candidateId\":77,\"sourceMessageId\":66}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.memoryId").value("56"));
        verify(client).persistenceRequest("memory-candidates/confirm", Map.of(
                "userId", 7L, "botId", 12L, "candidateId", 34L, "revision", 1L,
                "content", "喜欢治愈作品", "memoryType", "preference"));
    }

    @Test void rejectUsesPathCandidateAndPositiveRevision() throws Exception {
        login();
        when(client.persistenceRequest(eq("memory-candidates/reject"), anyMap())).thenReturn(
                mapper.readTree("{\"rejected\":true}"));
        mvc.perform(post("/ai/bots/12/memory-candidates/34/reject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":2,\"userId\":99,\"candidateId\":88}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rejected").value(true));
        verify(client).persistenceRequest("memory-candidates/reject", Map.of(
                "userId", 7L, "botId", 12L, "candidateId", 34L, "revision", 2L));
    }

    @Test void candidateSettingAndActionBodiesAreValidated() throws Exception {
        login();
        for (String body : new String[]{"{\"revision\":-1,\"enabled\":true}", "{\"revision\":0}", "{\"enabled\":true}"}) {
            mvc.perform(put("/ai/bots/12/memory-candidate-settings").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        for (String body : new String[]{"{\"revision\":0,\"content\":\"value\",\"memoryType\":\"fact\"}",
                "{\"revision\":1,\"content\":\" \",\"memoryType\":\"fact\"}",
                "{\"revision\":1,\"content\":\"value\",\"memoryType\":\"other\"}"}) {
            mvc.perform(post("/ai/bots/12/memory-candidates/34/confirm").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/ai/bots/12/memory-candidates/34/reject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test void queryLimitsCursorAndPathIdsAreValidated() throws Exception {
        login();
        mvc.perform(get("/ai/bots/12/memory-candidates").param("limit", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/ai/bots/12/memory-candidates").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/ai/bots/12/memory-candidates").param("beforeId", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/ai/bots/0/memory-candidate-settings")).andExpect(status().isBadRequest());
        mvc.perform(post("/ai/bots/12/memory-candidates/0/reject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test void confirmationLengthUsesUnicodeCodePoints() throws Exception {
        login();
        String content = new String(Character.toChars(0x1F600)).repeat(1000);
        mvc.perform(post("/ai/bots/12/memory-candidates/34/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("revision", 1, "content", content, "memoryType", "fact"))))
                .andExpect(status().isOk());
        verify(client).persistenceRequest("memory-candidates/confirm", Map.of(
                "userId", 7L, "botId", 12L, "candidateId", 34L, "revision", 1L, "content", content, "memoryType", "fact"));
        mvc.perform(post("/ai/bots/12/memory-candidates/34/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("revision", 1, "content", content + content.substring(0, 2), "memoryType", "fact"))))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(client);
    }

    @Test void backendNotFoundConflictAndSourceStatusArePreserved() throws Exception {
        login();
        when(client.persistenceRequest(eq("memory-candidates/settings/get"), anyMap()))
                .thenThrow(BusinessException.notFound("机器人不存在"));
        mvc.perform(get("/ai/bots/12/memory-candidate-settings")).andExpect(status().isNotFound());
        when(client.persistenceRequest(eq("memory-candidates/reject"), anyMap()))
                .thenThrow(BusinessException.conflict("配置已更新"));
        mvc.perform(post("/ai/bots/12/memory-candidates/34/reject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1}"))
                .andExpect(status().isConflict());
        when(client.persistenceRequest(eq("memory-candidates/confirm"), anyMap()))
                .thenThrow(BusinessException.conflict("来源会话或消息已失效"));
        mvc.perform(post("/ai/bots/12/memory-candidates/34/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1,\"content\":\"value\",\"memoryType\":\"fact\"}"))
                .andExpect(status().isConflict());
    }

    @Test void confirmationContentIsRedactedFromDtoToString() {
        assertFalse(new AiMemoryCandidateController.ConfirmCandidate(1L, "private memory", "fact")
                .toString().contains("private memory"));
    }
}
