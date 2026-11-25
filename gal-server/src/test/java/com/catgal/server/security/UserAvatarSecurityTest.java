package com.catgal.server.security;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.controller.UserController;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mvc.LoginRequiredInterceptor;
import com.catgal.server.service.IUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserAvatarSecurityTest {
    private final IUserService users = mock(IUserService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new UserController(users, mock(com.catgal.server.service.IAuthSessionService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new LoginRequiredInterceptor(new CurrentUserProvider())).build();
    }

    @AfterEach
    void clear() { UserContext.clear(); }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1});
    }

    @Test
    void anonymousRequestIsRejectedBeforeStorage() throws Exception {
        mvc.perform(multipart("/user/avatar").file(file())).andExpect(status().isUnauthorized());
        verifyNoInteractions(users);
    }

    @Test
    void missingFileIs400() throws Exception {
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        mvc.perform(multipart("/user/avatar")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(users);
    }

    @Test
    void multipartLimitFailuresAre413() throws Exception {
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        when(users.updateAvatar(any())).thenThrow(new MaxUploadSizeExceededException(2 * 1024 * 1024));
        mvc.perform(multipart("/user/avatar").file(file())).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(413));
    }

    @Test
    void storageFailureUsesTheGlobalBusinessErrorResponse() throws Exception {
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        when(users.updateAvatar(any())).thenThrow(BusinessException.internalError("上传头像失败"));
        mvc.perform(multipart("/user/avatar").file(file())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.msg").value("上传头像失败"));
    }
}
