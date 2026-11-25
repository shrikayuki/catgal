package com.catgal.server.ai;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.controller.AiSessionController;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mvc.LoginRequiredInterceptor;
import com.catgal.server.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiSessionControllerTest {
    private final AiServiceClient client = mock(AiServiceClient.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiSessionController(client))
            .addInterceptors(new LoginRequiredInterceptor(new CurrentUserProvider()))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach void clear() { UserContext.clear(); }
    private void login() { UserContext.setUser(UserInfo.builder().userId(7L).build()); }

    @Test void anonymousSessionListRequiresLogin() throws Exception {
        mvc.perform(get("/ai/sessions")).andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }

    @Test void createTakesUserIdentityFromLoginContext() throws Exception {
        login();
        when(client.persistenceRequest(eq("sessions/create"), anyMap()))
                .thenReturn(new ObjectMapper().readTree("{\"sessionId\":\"s1\"}"));
        mvc.perform(post("/ai/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"girlCode\":\"emily\",\"userId\":99}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sessionId").value("s1"));
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).persistenceRequest(eq("sessions/create"), payload.capture());
        assertEquals(7L, payload.getValue().get("userId"));
    }

    @Test void missingIdempotenceKeyIsJsonBadRequest() throws Exception {
        login();
        mvc.perform(post("/ai/sessions/s1/messages").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        verifyNoInteractions(client);
    }

    @Test void ownershipFailureIsJsonBeforeStreamStarts() throws Exception {
        login();
        when(client.persistenceRequest(eq("sessions/get"), anyMap()))
                .thenThrow(BusinessException.notFound("会话不存在"));
        mvc.perform(post("/ai/sessions/s1/messages").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"clientMessageId\":\"key-1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        verify(client, never()).persistentChat(anyMap());
    }

    @Test void emitsPersistentMessageMetadata() throws Exception {
        login();
        when(client.persistentChat(anyMap())).thenReturn(Flux.just(
                new AiChatEvent("meta", "req-1", "s1", null, null, null,
                        "2", "1", false, "running", "qa-model"),
                AiChatEvent.delta("req-1", "hello"), AiChatEvent.done("req-1")));
        MvcResult pending = mvc.perform(post("/ai/sessions/s1/messages").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"clientMessageId\":\"key-1\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(5000);
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().string(containsString("\"messageId\":\"2\"")))
                .andExpect(content().string(containsString("event:done")));
    }

    @Test void semanticChoiceRequiresToolsAndIsPassedOnlyWhenRequested() throws Exception {
        login();
        mvc.perform(post("/ai/sessions/s1/messages").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"clientMessageId\":\"key-1\",\"useSemanticSearch\":true}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
        login();
        when(client.persistentChat(anyMap())).thenReturn(Flux.just(AiChatEvent.done("req")));
        MvcResult pending = mvc.perform(post("/ai/sessions/s1/messages").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"clientMessageId\":\"key-2\",\"useGameTools\":true,\"useSemanticSearch\":true}"))
                .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(5000);
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
        verify(client).persistentChat(argThat(payload -> Boolean.TRUE.equals(payload.get("useSemanticSearch"))
                && Long.valueOf(7).equals(payload.get("userId"))));
    }
}
