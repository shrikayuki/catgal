package com.catgal.server.loadtest;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.server.controller.admin.AdminLoadTestController;
import com.catgal.server.domain.po.User;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.mvc.AdminInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminLoadTestControllerTest {
    private final LoadTestService service = mock(LoadTestService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminLoadTestController(service))
            .addInterceptors(new AdminInterceptor(users)).setControllerAdvice(new GlobalExceptionHandler()).build();
    @AfterEach void clear() { UserContext.clear(); }

    private void admin() {
        UserContext.setUser(UserInfo.builder().userId(6L).role(2).build());
        User actor = new User(); actor.setId(6L); actor.setRole(2);
        when(users.selectById(6L)).thenReturn(actor);
    }
    private Map<String, Object> body() {
        return new LinkedHashMap<>(Map.of("clientRequestId", UUID.randomUUID().toString(), "scenarioId", "games",
                "concurrency", 10, "durationSeconds", 10, "maxRequests", 100));
    }

    @Test void allRoutesRequireAdminAndCurrentDatabaseRoleOverridesToken() throws Exception {
        mvc.perform(get("/admin/load-tests/scenarios")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/load-tests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/load-tests/id")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/load-tests/id/stop")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/load-tests").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body())))
                .andExpect(status().isUnauthorized());
        admin();
        User downgraded = new User(); downgraded.setId(6L); downgraded.setRole(0);
        when(users.selectById(6L)).thenReturn(downgraded);
        mvc.perform(get("/admin/load-tests/scenarios")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void creationUsesAuthenticatedActorAndPreservesBoundedDto() throws Exception {
        admin();
        Map<String, Object> input = body(); input.put("actorId", 999); input.put("target", "https://external.invalid");
        mvc.perform(post("/admin/load-tests").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input)))
                .andExpect(status().isOk());
        verify(service).start(argThat(value -> value.concurrency() == 10 && value.scenarioId().equals("games")), eq(6L));
    }

    @Test void rejectsLimitsAndMalformedIdsBeforeStartingWork() throws Exception {
        admin();
        for (Map<String, Object> change : java.util.List.of(Map.<String, Object>of("concurrency", 51),
                Map.<String, Object>of("maxRequests", 5001), Map.<String, Object>of("durationSeconds", 0),
                Map.<String, Object>of("clientRequestId", "invalid"), Map.<String, Object>of("gameId", -1))) {
            Map<String, Object> input = body(); input.putAll(change);
            mvc.perform(post("/admin/load-tests").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
