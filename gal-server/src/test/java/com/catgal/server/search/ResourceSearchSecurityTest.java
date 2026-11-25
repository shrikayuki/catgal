package com.catgal.server.search;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.server.controller.admin.AdminResourceSearchController;
import com.catgal.server.domain.po.User;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.mvc.AdminInterceptor;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResourceSearchSecurityTest {
    private final UserMapper users = mock(UserMapper.class);
    private final ResourceSearchMaintenance maintenance = mock(ResourceSearchMaintenance.class);
    private final ResourceSearchOutbox outbox = mock(ResourceSearchOutbox.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminResourceSearchController(maintenance, outbox))
            .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new AdminInterceptor(users)).build();

    @AfterEach
    void clear() { UserContext.clear(); }

    @Test
    void anonymousRequestsCannotInspectOrRebuildResourceIndex() throws Exception {
        UserContext.clear();
        mvc.perform(get("/admin/search/resources/outbox")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/search/resources/rebuild")).andExpect(status().isUnauthorized());
        verifyNoInteractions(outbox, maintenance);
    }

    @Test
    void ordinaryUserCannotRebuildOrRetry() throws Exception {
        UserContext.setUser(UserInfo.builder().userId(7L).role(0).build());
        User user = new User();
        user.setId(7L);
        user.setRole(0);
        when(users.selectById(7L)).thenReturn(user);
        mvc.perform(post("/admin/search/resources/rebuild")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/search/resources/retry")).andExpect(status().isForbidden());
        verifyNoInteractions(outbox, maintenance);
    }

    @Test
    void administratorCanInspectBacklog() throws Exception {
        UserContext.setUser(UserInfo.builder().userId(6L).role(2).build());
        User user = new User();
        user.setId(6L);
        user.setRole(2);
        when(users.selectById(6L)).thenReturn(user);
        when(outbox.status()).thenReturn(Map.of("pending", 0));
        mvc.perform(get("/admin/search/resources/outbox")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pending").value(0));
    }
}
