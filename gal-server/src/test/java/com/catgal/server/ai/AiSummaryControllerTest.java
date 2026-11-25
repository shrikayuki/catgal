package com.catgal.server.ai;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.controller.AiSummaryController;
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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiSummaryControllerTest {
    private final AiServiceClient client = mock(AiServiceClient.class);
    private final AiServiceProperties properties = new AiServiceProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(validatedController())
            .addInterceptors(new LoginRequiredInterceptor(new CurrentUserProvider()))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    private Object validatedController() {
        ProxyFactory proxy = new ProxyFactory(new AiSummaryController(client, properties));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new MethodValidationInterceptor());
        return proxy.getProxy();
    }

    @AfterEach void clear() { UserContext.clear(); }
    private void login() {
        UserContext.setUser(UserInfo.builder().userId(7L).build());
        properties.setSessionSummaryEnabled(true);
    }

    @Test void summaryOperationsRequireLogin() throws Exception {
        mvc.perform(get("/ai/sessions/session-1/summary")).andExpect(status().isUnauthorized());
        mvc.perform(put("/ai/sessions/session-1/summary").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/ai/sessions/session-1/summary").param("revision", "0"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }

    @Test void featureDefaultsToDisabled() throws Exception {
        login();
        properties.setSessionSummaryEnabled(false);
        mvc.perform(get("/ai/sessions/session-1/summary")).andExpect(status().isServiceUnavailable());
        mvc.perform(put("/ai/sessions/session-1/summary").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"enabled\":true}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(delete("/ai/sessions/session-1/summary").param("revision", "0"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(client);
    }

    @Test void getUsesAuthenticatedUserAndPathSession() throws Exception {
        login();
        when(client.persistenceRequest("summary/get", Map.of("userId", 7L, "sessionId", "session-1")))
                .thenReturn(mapper.readTree("{\"sessionId\":\"session-1\",\"enabled\":true,\"revision\":\"0\",\"summary\":null,\"status\":\"empty\",\"coveredTurns\":0,\"coveredThroughMessageId\":null,\"sourceMemoryVersion\":null,\"lastErrorCode\":null,\"updateTime\":null}"));
        mvc.perform(get("/ai/sessions/session-1/summary").param("userId", "99").param("sessionId", "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sessionId").value("session-1"))
                .andExpect(jsonPath("$.data.revision").value("0"))
                .andExpect(jsonPath("$.data.status").value("empty"));
        verify(client).persistenceRequest("summary/get", Map.of("userId", 7L, "sessionId", "session-1"));
    }

    @Test void initialZeroRevisionAndFalseSettingAreForwarded() throws Exception {
        login();
        mvc.perform(put("/ai/sessions/session-1/summary").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"enabled\":false,\"userId\":99,\"sessionId\":\"other\"}"))
                .andExpect(status().isOk());
        verify(client).persistenceRequest("summary/settings", Map.of(
                "userId", 7L, "sessionId", "session-1", "revision", 0L, "enabled", false));
    }

    @Test void clearAcceptsInitialZeroRevisionAndUsesPathIdentity() throws Exception {
        login();
        mvc.perform(delete("/ai/sessions/session-1/summary").param("revision", "0").param("userId", "99"))
                .andExpect(status().isOk());
        verify(client).persistenceRequest("summary/clear", Map.of(
                "userId", 7L, "sessionId", "session-1", "revision", 0L));
    }

    @Test void negativeOrMissingRevisionAndMissingEnabledFailBeforeProxy() throws Exception {
        login();
        for (String body : new String[]{"{\"revision\":-1,\"enabled\":true}",
                "{\"revision\":0}", "{\"enabled\":true}"}) {
            mvc.perform(put("/ai/sessions/session-1/summary").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(delete("/ai/sessions/session-1/summary").param("revision", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/ai/sessions/session-1/summary")).andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test void invalidSessionIdentifiersFailBeforeProxy() throws Exception {
        login();
        mvc.perform(get("/ai/sessions/not.allowed/summary")).andExpect(status().isBadRequest());
        mvc.perform(get("/ai/sessions/" + "a".repeat(257) + "/summary")).andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test void ownershipAndRevisionFailuresKeepBackendStatus() throws Exception {
        login();
        when(client.persistenceRequest(eq("summary/get"), anyMap())).thenThrow(BusinessException.notFound("会话不存在"));
        mvc.perform(get("/ai/sessions/session-1/summary")).andExpect(status().isNotFound());
        when(client.persistenceRequest(eq("summary/clear"), anyMap())).thenThrow(BusinessException.conflict("配置已更新"));
        mvc.perform(delete("/ai/sessions/session-1/summary").param("revision", "2"))
                .andExpect(status().isConflict());
    }
}
