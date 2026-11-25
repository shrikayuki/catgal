package com.catgal.server.catalog;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.controller.admin.AdminCompanyController;
import com.catgal.server.controller.admin.AdminGameCatalogController;
import com.catgal.server.controller.admin.AdminTagController;
import com.catgal.server.controller.admin.AdminExternalCatalogController;
import com.catgal.server.catalog.external.ExternalCatalogService;
import com.catgal.server.catalog.external.ExternalLinkStore;
import com.catgal.server.domain.po.User;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.service.ICompanyService;
import com.catgal.server.service.ITagService;
import com.catgal.server.mvc.AdminInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogSecurityTest {
    private final UserMapper users = mock(UserMapper.class);
    private final ICompanyService companies = mock(ICompanyService.class);
    private final ITagService tags = mock(ITagService.class);
    private final GameCatalogService games = mock(GameCatalogService.class);
    private final ExternalCatalogService externalCatalog = mock(ExternalCatalogService.class);
    private final ExternalLinkStore externalLinks = mock(ExternalLinkStore.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminCompanyController(companies),
                    new AdminTagController(tags), new AdminGameCatalogController(games),
                    new AdminExternalCatalogController(externalCatalog, externalLinks))
            .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new AdminInterceptor(users)).build();

    @AfterEach
    void clear() { UserContext.clear(); }

    private void login(int role) {
        UserContext.setUser(UserInfo.builder().userId(6L).role(role).build());
        User user = new User();
        user.setId(6L);
        user.setRole(role);
        when(users.selectById(6L)).thenReturn(user);
    }

    @Test
    void anonymousRequestsCannotReadOrMutateAdminCatalog() throws Exception {
        UserContext.clear();
        mvc.perform(get("/admin/companies")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/tags").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"标签\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/games").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"游戏\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(companies, tags, games);
    }

    @Test
    void ordinaryUserCannotDeleteOrReplaceRelations() throws Exception {
        login(0);
        mvc.perform(delete("/admin/companies/1?revision=0")).andExpect(status().isForbidden());
        mvc.perform(put("/admin/games/1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":0,\"ids\":[]}")).andExpect(status().isForbidden());
        verifyNoInteractions(companies, tags, games);
    }

    @Test
    void invalidBodiesAreRejectedBeforeAnyMutation() throws Exception {
        login(2);
        mvc.perform(post("/admin/games").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/admin/games/1/tags").contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":0,\"ids\":[null]}")).andExpect(status().isBadRequest());
        mvc.perform(put("/admin/companies/1").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Key\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(companies, tags, games);
    }

    @Test
    void staleRevisionIsReportedAsHttpConflict() throws Exception {
        login(2);
        when(companies.updateCatalog(eq(1L), anyString(), eq(0L)))
                .thenThrow(BusinessException.conflict("资料已修改"));
        mvc.perform(put("/admin/companies/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Key\",\"revision\":0}")).andExpect(status().isConflict());
    }

    @Test
    void externalSearchAndBindingRequireAdministrator() throws Exception {
        UserContext.clear();
        mvc.perform(get("/admin/catalog/sources/vndb/search?keyword=Ever17")).andExpect(status().isUnauthorized());
        login(0);
        mvc.perform(put("/admin/games/1/external-links/vndb").contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":0,\"externalId\":\"v17\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(externalCatalog, externalLinks);
    }

    @Test
    void invalidLinkPayloadIsRejectedBeforeRemoteRequest() throws Exception {
        login(2);
        mvc.perform(put("/admin/games/1/external-links/vndb").contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalId\":\"v17\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(externalCatalog, externalLinks);
    }
}
