package com.catgal.server.security;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.server.controller.admin.AdminGameCoverController;
import com.catgal.server.domain.po.User;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.mvc.AdminInterceptor;
import com.catgal.server.service.IStorageFileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GameCoverSecurityTest {
    private final IStorageFileService files = mock(IStorageFileService.class);
    private final UserMapper users = mock(UserMapper.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminGameCoverController(files))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new AdminInterceptor(users)).build();
    }

    @AfterEach void clear() { UserContext.clear(); }

    private void login(int databaseRole) {
        UserContext.setUser(UserInfo.builder().userId(6L).role(2).build());
        User user = new User();
        user.setRole(databaseRole);
        when(users.selectById(6L)).thenReturn(user);
    }

    private MockMultipartFile image() { return new MockMultipartFile("file", "cover.png", "image/png", new byte[]{1}); }

    @Test void anonymousAndDowngradedCallersCannotUpload() throws Exception {
        mvc.perform(multipart("/admin/games/10/cover").file(image()).param("revision", "0")).andExpect(status().isUnauthorized());
        login(0);
        mvc.perform(multipart("/admin/games/10/cover").file(image()).param("revision", "0")).andExpect(status().isForbidden());
        verifyNoInteractions(files);
    }

    @Test void fileAndRevisionAreBothRequired() throws Exception {
        login(2);
        mvc.perform(multipart("/admin/games/10/cover").param("revision", "0")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/admin/games/10/cover").file(image())).andExpect(status().isBadRequest());
        verifyNoInteractions(files);
    }

    @Test void uploadAndClearReturnTheNewRevisionAndFileReference() throws Exception {
        login(2);
        String id = "a".repeat(32);
        when(files.updateGameCover(eq(10L), eq(0L), any())).thenReturn(new IStorageFileService.UpdateResult(id, "/api/files/game-covers/" + id, 1L));
        mvc.perform(multipart("/admin/games/10/cover").file(image()).param("revision", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fileId").value(id)).andExpect(jsonPath("$.data.revision").value(1));
        when(files.clearGameCover(10L, 1L)).thenReturn(new IStorageFileService.UpdateResult(null, null, 2L));
        mvc.perform(delete("/admin/games/10/cover").param("revision", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
    }
}
