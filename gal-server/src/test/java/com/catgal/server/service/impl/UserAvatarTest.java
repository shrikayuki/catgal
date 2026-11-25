package com.catgal.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.StorageFile;
import com.catgal.server.domain.po.User;
import com.catgal.server.mapper.StorageFileMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IObjectStorageService;
import com.catgal.server.storage.AvatarImageValidator;
import com.catgal.server.storage.FileAccessPaths;
import com.catgal.server.storage.StorageProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Retains the eleven upload failure scenarios while moving persistence decisions to the storage service. */
@SuppressWarnings({"unchecked", "rawtypes"})
class UserAvatarTest {
    private final IObjectStorageService objects = mock(IObjectStorageService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final StorageFileMapper files = mock(StorageFileMapper.class);
    private final StorageProperties properties = new StorageProperties();
    private final StorageFileServiceImpl service = new StorageFileServiceImpl(objects, new CurrentUserProvider(),
            properties, new AvatarImageValidator(), mock(PlatformTransactionManager.class),
            List.of(new AvatarFileBindingServiceImpl(users, files)));
    private final Map<String, StorageFile> records = new HashMap<>();
    private User original;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StorageFile.class);
        ReflectionTestUtils.setField(service, "baseMapper", files);
        properties.setEnabled(true);
        when(files.currentTime()).thenAnswer(call -> LocalDateTime.now(ZoneOffset.UTC));
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        original = new User();
        original.setId(6L);
        original.setAvatarUrl("https://assets.example/avatars/old.png");
        when(users.selectById(6L)).thenReturn(original);
        when(users.selectForUpdate(6L)).thenAnswer(call -> {
            User copy = new User();
            BeanUtils.copyProperties(original, copy);
            return copy;
        });
        when(files.insert(any(StorageFile.class))).thenAnswer(call -> {
            StorageFile file = call.getArgument(0);
            records.put(file.getId(), copy(file));
            return 1;
        });
        when(files.selectById(anyString())).thenAnswer(call -> copy(records.get(call.getArgument(0))));
        when(files.selectForUpdate(anyString())).thenAnswer(call -> copy(records.get(call.getArgument(0))));
        when(files.updateById(any(StorageFile.class))).thenAnswer(call -> {
            StorageFile file = call.getArgument(0);
            records.put(file.getId(), copy(file));
            return 1;
        });
        when(files.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(users.update(isNull(), any(Wrapper.class))).thenAnswer(call -> {
            LambdaUpdateWrapper<User> wrapper = call.getArgument(1);
            original.setAvatarFileId(wrapper.getParamNameValuePairs().values().stream()
                    .filter(value -> value instanceof String && ((String) value).matches("[0-9a-f]{32}"))
                    .map(String.class::cast).findFirst().orElseThrow());
            original.setAvatarUrl(null);
            return 1;
        });
    }

    @AfterEach
    void clear() { UserContext.clear(); }

    private static StorageFile copy(StorageFile source) {
        if (source == null) return null;
        StorageFile copy = new StorageFile();
        BeanUtils.copyProperties(source, copy);
        return copy;
    }

    private MultipartFile file() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", bytes);
            return new MockMultipartFile("file", "avatar.PNG", "image/png", bytes.toByteArray());
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private StorageFile onlyFile() { assertEquals(1, records.size()); return records.values().iterator().next(); }
    private void makeCleanupDue(StorageFile file) { file.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)); }

    @Test
    void successfulUploadUsesUniqueOwnedKeysAndChecksDatabaseUpdate() {
        String first = service.updateAvatar(file());
        String second = service.updateAvatar(file());
        assertNotEquals(first, second);
        assertEquals(2, records.size());
        assertNull(original.getAvatarUrl());
        assertEquals(FileAccessPaths.avatar(original.getAvatarFileId()), second);
        assertTrue(records.values().stream().allMatch(item -> item.getOwnerId().equals(6L)
                && item.getObjectKey().matches("v1/avatars/[0-9]{4}/[0-9]{2}/[0-9]{2}/6/[a-f0-9]{32}\\.png")
                && item.getSha256().matches("[0-9a-f]{64}") && item.getSizeBytes() > 0));
        ArgumentCaptor<Wrapper<User>> updates = ArgumentCaptor.forClass(Wrapper.class);
        verify(users, times(2)).update(isNull(), updates.capture());
        assertTrue(updates.getValue().getCustomSqlSegment().contains("id"));
        assertTrue(((LambdaUpdateWrapper<User>) updates.getValue()).getParamNameValuePairs().values().containsAll(List.of(6L, original.getAvatarFileId())));
        verify(objects, times(2)).put(eq("catgal-media"), anyString(), any(java.io.InputStream.class), anyLong(), eq("image/png"));
        verify(objects, never()).delete(anyString(), anyString());
    }

    @Test
    void uploadFailureReturns500AndCompensatesOnlyItsNewKey() {
        RuntimeException failure = new IllegalStateException("upload unavailable");
        doThrow(failure).when(objects).put(anyString(), anyString(), any(java.io.InputStream.class), anyLong(), anyString());
        BusinessException error = assertThrows(BusinessException.class, () -> service.updateAvatar(file()));
        assertEquals(500, error.getHttpStatus());
        assertSame(failure, error.getCause());
        StorageFile pending = onlyFile();
        assertEquals("CLEANUP_PENDING", pending.getStatus());
        assertTrue(pending.getNextAttemptAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)));
        verify(users, never()).update(any(), any(Wrapper.class));
        verify(objects, never()).delete(anyString(), anyString());
        makeCleanupDue(pending);
        service.cleanup(pending.getId());
        verify(objects).delete(pending.getBucket(), pending.getObjectKey());
    }

    @Test
    void zeroUpdatedRowsCannotBeReportedAsSuccessful() {
        when(users.update(isNull(), any(Wrapper.class))).thenReturn(0);
        assertEquals(500, assertThrows(BusinessException.class, () -> service.updateAvatar(file())).getHttpStatus());
        StorageFile pending = onlyFile();
        verify(objects).delete(pending.getBucket(), pending.getObjectKey());
        assertEquals("https://assets.example/avatars/old.png", original.getAvatarUrl());
    }

    @Test
    void knownDatabaseFailureDeletesTheNewObject() {
        RuntimeException failure = new DataAccessResourceFailureException("isolated write failure");
        when(users.update(isNull(), any(Wrapper.class))).thenThrow(failure);
        BusinessException error = assertThrows(BusinessException.class, () -> service.updateAvatar(file()));
        assertSame(failure, error.getCause());
        StorageFile pending = onlyFile();
        verify(objects).delete(pending.getBucket(), pending.getObjectKey());
        assertNull(original.getAvatarFileId());
        assertEquals("https://assets.example/avatars/old.png", original.getAvatarUrl());
    }

    @Test
    void confirmedCommitAfterLostResponseReturnsSavedUrlWithoutDeletingIt() {
        when(users.update(isNull(), any(Wrapper.class))).thenAnswer(call -> {
            original.setAvatarFileId(onlyFile().getId());
            throw new DataAccessResourceFailureException("commit response lost");
        });
        String path = service.updateAvatar(file());
        assertEquals(FileAccessPaths.avatar(onlyFile().getId()), path);
        verify(objects, never()).delete(anyString(), anyString());
        assertEquals("READY", onlyFile().getStatus());
    }

    @Test
    void unknownDatabaseOutcomeKeepsObjectAndOriginalFailure() {
        RuntimeException failure = new DataAccessResourceFailureException("write response lost");
        RuntimeException verification = new DataAccessResourceFailureException("verification unavailable");
        when(files.updateById(any(StorageFile.class))).thenThrow(failure);
        when(users.selectById(6L)).thenThrow(verification);
        BusinessException error = assertThrows(BusinessException.class, () -> service.updateAvatar(file()));
        assertSame(failure, error.getCause());
        assertArrayEquals(new Throwable[]{verification}, failure.getSuppressed());
        verify(objects, never()).delete(anyString(), anyString());
        assertEquals("UPLOADING", onlyFile().getStatus());
    }

    @Test
    void failedCompensationDoesNotReplaceTheOriginalUploadFailure() {
        RuntimeException failure = new IllegalStateException("upload failed");
        doThrow(failure).when(objects).put(anyString(), anyString(), any(java.io.InputStream.class), anyLong(), anyString());
        BusinessException error = assertThrows(BusinessException.class, () -> service.updateAvatar(file()));
        assertSame(failure, error.getCause());
        StorageFile pending = onlyFile();
        makeCleanupDue(pending);
        doThrow(new IllegalStateException("cleanup unavailable")).when(objects).delete(anyString(), anyString());
        service.cleanup(pending.getId());
        assertSame(failure, error.getCause());
        assertEquals("CLEANUP_PENDING", onlyFile().getStatus());
        assertEquals(1, onlyFile().getCleanupAttempts());
        assertTrue(onlyFile().getNextAttemptAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)));
        verify(objects).delete(pending.getBucket(), pending.getObjectKey());
    }

    @Test
    void accountRemovedDuringUploadStillCleansOnlyTheNewObject() {
        when(users.selectForUpdate(6L)).thenReturn(original, null);
        when(users.selectById(6L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.updateAvatar(file()));
        StorageFile pending = onlyFile();
        verify(objects).delete(pending.getBucket(), pending.getObjectKey());
    }

    @Test
    void anonymousAndMissingAccountsCannotUpload() {
        UserContext.clear();
        assertEquals(401, assertThrows(BusinessException.class, () -> service.updateAvatar(file())).getHttpStatus());
        UserContext.setUser(UserInfo.builder().userId(6L).build());
        when(users.selectForUpdate(6L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.updateAvatar(file())).getHttpStatus());
        verifyNoInteractions(objects);
        assertTrue(records.isEmpty());
    }

    @Test
    void nullEmptyUnsupportedAndOversizedInputsAreRejected() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service.updateAvatar(null)).getHttpStatus());
        for (MultipartFile invalid : List.of(new MockMultipartFile("file", new byte[0]),
                new MockMultipartFile("file", "script.exe", "image/png", new byte[]{1}),
                new MockMultipartFile("file", "avatar.png", "application/pdf", new byte[]{1}),
                new MockMultipartFile("file", "avatar.png", "image/png", new byte[2 * 1024 * 1024 + 1]))) {
            assertEquals(400, assertThrows(BusinessException.class, () -> service.updateAvatar(invalid)).getHttpStatus());
        }
        verifyNoInteractions(objects);
    }

    @Test
    void readFailureCannotCallObjectStorage() throws Exception {
        MultipartFile invalid = mock(MultipartFile.class);
        when(invalid.getOriginalFilename()).thenReturn("avatar.png");
        when(invalid.getContentType()).thenReturn("image/png");
        when(invalid.getBytes()).thenThrow(new IOException("isolated read error"));
        assertEquals(500, assertThrows(BusinessException.class, () -> service.updateAvatar(invalid)).getHttpStatus());
        verifyNoInteractions(objects);
    }

    @Test
    void cleanupNeverDeletesAReferencedAvatarOrAnActiveUpload() {
        service.updateAvatar(file());
        StorageFile active = onlyFile();
        makeCleanupDue(active);
        service.cleanup(active.getId());
        verify(objects, never()).delete(anyString(), anyString());
        original.setAvatarFileId(null);
        active.setStatus("UPLOADING");
        active.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        service.cleanup(active.getId());
        verify(objects, never()).delete(anyString(), anyString());
    }

    @Test
    void cleanupClaimsAreGuardedByStatusAndAttemptVersion() {
        service.updateAvatar(file());
        StorageFile pending = onlyFile();
        original.setAvatarFileId(null);
        makeCleanupDue(pending);
        service.cleanup(pending.getId());
        ArgumentCaptor<Wrapper<StorageFile>> update = ArgumentCaptor.forClass(Wrapper.class);
        verify(files).update(isNull(), update.capture());
        assertTrue(update.getValue().getCustomSqlSegment().contains("cleanup_attempts"));
        assertTrue(update.getValue().getCustomSqlSegment().contains("status"));
        assertTrue(((LambdaUpdateWrapper<StorageFile>) update.getValue()).getParamNameValuePairs().values().containsAll(List.of(pending.getId(), "CLEANUP_PENDING", 1, "DELETED")));
    }

    @Test
    void publicReadUsesMetadataAndRejectsInvalidOrInactiveIdentifiers() {
        service.updateAvatar(file());
        StorageFile stored = onlyFile();
        when(files.selectPublicAvatar(stored.getId())).thenReturn(stored);
        var content = new java.io.ByteArrayInputStream(new byte[]{1});
        when(objects.open(stored.getBucket(), stored.getObjectKey())).thenReturn(content);
        var download = service.openAvatar(stored.getId());
        assertSame(content, download.content());
        assertEquals(stored.getSizeBytes(), download.size());
        assertEquals("image/png", download.contentType());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.openAvatar("../private")).getHttpStatus());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.openAvatar("f".repeat(32))).getHttpStatus());
    }
}
