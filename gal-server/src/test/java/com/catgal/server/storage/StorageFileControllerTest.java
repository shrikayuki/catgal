package com.catgal.server.storage;

import com.catgal.common.exception.BusinessException;
import com.catgal.server.controller.StorageFileController;
import com.catgal.server.exception.GlobalExceptionHandler;
import com.catgal.server.service.IStorageFileService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StorageFileControllerTest {
    private final IStorageFileService files = mock(IStorageFileService.class);
    private final String id = "a".repeat(32);

    @Test
    void publicImageStreamsWithExplicitTypeAndClosesItsResource() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        var input = new ByteArrayInputStream(new byte[]{1, 2}) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        };
        when(files.openAvatar(id)).thenReturn(new IStorageFileService.Download(input, 2, "image/png"));
        MockMvcBuilders.standaloneSetup(new StorageFileController(files)).build()
                .perform(get("/files/avatars/" + id)).andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{1, 2}))
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Length", "2"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        assertTrue(closed.get());
    }

    @Test
    void inactiveFilesAndUpstreamErrorsUseTheGlobalResponse() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new StorageFileController(files))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(files.openAvatar(id)).thenThrow(BusinessException.notFound("头像不存在"));
        mvc.perform(get("/files/avatars/" + id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        doThrow(BusinessException.internalError("头像暂时无法读取")).when(files).openAvatar(id);
        mvc.perform(get("/files/avatars/" + id)).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500));
    }
}
