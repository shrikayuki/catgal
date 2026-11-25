package com.catgal.server.security;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.server.ai.AiServiceProperties;
import com.catgal.server.controller.AiBotImageController;
import com.catgal.server.controller.StorageFileController;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.mvc.LoginRequiredInterceptor;
import com.catgal.server.service.IStorageFileService;
import com.catgal.server.storage.AiBotImageKind;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiBotImageSecurityTest {
    private final IStorageFileService files = mock(IStorageFileService.class);
    private final AiServiceProperties properties = new AiServiceProperties();
    private MockMvc mvc;
    private final String id = "b".repeat(32);

    @BeforeEach void setup() {
        properties.setPersonalizationEnabled(true);
        mvc = MockMvcBuilders.standaloneSetup(new AiBotImageController(files, properties), new StorageFileController(files))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new LoginRequiredInterceptor(new CurrentUserProvider())).build();
    }

    @AfterEach void clear() { UserContext.clear(); }

    private MockMultipartFile image() {
        return new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1});
    }

    @Test void anonymousUploadsAndImageReadsAreRejectedBeforeStorage() throws Exception {
        mvc.perform(multipart("/ai/bots/10/images/avatar").file(image()).param("revision", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/files/ai-bot-avatars/" + id)).andExpect(status().isUnauthorized());
        verifyNoInteractions(files);
    }

    @Test void uploadReturnsTheUrlAndAStringRevision() throws Exception {
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        when(files.updateAiBotImage(eq(10L), eq(AiBotImageKind.AVATAR), eq(1L), any()))
                .thenReturn(new IStorageFileService.UpdateResult(id, AiBotImageKind.AVATAR.path(id), 2L));
        mvc.perform(multipart("/ai/bots/10/images/avatar").file(image()).param("revision", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.url").value(AiBotImageKind.AVATAR.path(id)))
                .andExpect(jsonPath("$.data.revision").value("2"));
    }

    @Test void clearingReturnsAnEmptyUrl() throws Exception {
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        when(files.clearAiBotImage(10L, AiBotImageKind.PORTRAIT, 2L))
                .thenReturn(new IStorageFileService.UpdateResult(null, null, 3L));
        mvc.perform(delete("/ai/bots/10/images/portrait").param("revision", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.url").value(""))
                .andExpect(jsonPath("$.data.revision").value("3"));
    }

    @Test void disabledFeatureAndUnsupportedKindsDoNotUpload() throws Exception {
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        mvc.perform(multipart("/ai/bots/10/images/other").file(image()).param("revision", "1"))
                .andExpect(status().isBadRequest());
        properties.setPersonalizationEnabled(false);
        mvc.perform(multipart("/ai/bots/10/images/avatar").file(image()).param("revision", "1"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(files);
    }

    @Test void privateImagesDisableCachingAndRetainTheValidatedMimeType() throws Exception {
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        when(files.openAiBotImage(AiBotImageKind.PORTRAIT, id)).thenReturn(
                new IStorageFileService.Download(new ByteArrayInputStream(new byte[]{1}), 1, "image/png"));
        mvc.perform(get("/files/ai-bot-portraits/" + id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
