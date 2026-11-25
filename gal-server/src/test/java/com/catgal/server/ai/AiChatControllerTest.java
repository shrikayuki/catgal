package com.catgal.server.ai;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.controller.AiGirlController;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mvc.LoginRequiredInterceptor;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IAiGirlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiChatControllerTest {
    private final IAiGirlService service = mock(IAiGirlService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiGirlController(service))
            .addInterceptors(new LoginRequiredInterceptor(new CurrentUserProvider()))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach void clear() { UserContext.clear(); }

    private void login() { UserContext.setUser(UserInfo.builder().userId(7L).build()); }

    @Test void anonymousSseRequestGetsJsonUnauthorizedBeforeStreamStarts() throws Exception {
        mvc.perform(post("/ai-girl/chat/emily/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"s1\",\"content\":\"hi\"}"))
                .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        verifyNoInteractions(service);
    }

    @Test void invalidJsonBodyGetsJsonBadRequestBeforeStreamStarts() throws Exception {
        login();
        mvc.perform(post("/ai-girl/chat/emily/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"s1\",\"content\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        verifyNoInteractions(service);
    }

    @Test void unownedSessionGetsJsonNotFoundBeforeStreamStarts() throws Exception {
        login();
        when(service.streamChat("emily", "s1", "hi")).thenThrow(BusinessException.notFound("会话不存在"));
        mvc.perform(post("/ai-girl/chat/emily/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"s1\",\"content\":\"hi\"}"))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test void serializesStructuredEventsAndDisablesProxyBuffering() throws Exception {
        login();
        when(service.streamChat("emily", "s1", "hi")).thenReturn(Flux.just(AiChatEvent.meta("request-1", "s1"),
                AiChatEvent.delta("request-1", "你好"), AiChatEvent.done("request-1")));
        MvcResult pending = mvc.perform(post("/ai-girl/chat/emily/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"s1\",\"content\":\"hi\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(5000);
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:meta")))
                .andExpect(content().string(containsString("event:delta")))
                .andExpect(content().string(containsString("event:done")));
    }
}
