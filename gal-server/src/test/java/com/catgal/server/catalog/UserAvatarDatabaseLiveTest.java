package com.catgal.server.catalog;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.StorageFile;
import com.catgal.server.mapper.StorageFileMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IObjectStorageService;
import com.catgal.server.service.IStorageFileService;
import com.catgal.server.service.impl.StorageFileServiceImpl;
import com.catgal.server.storage.AvatarImageValidator;
import com.catgal.server.storage.FileAccessPaths;
import com.catgal.server.storage.StorageProperties;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real committed MySQL transactions and scoped fixture cleanup, with object storage faults controlled locally. */
@SpringJUnitConfig({CatalogDatabaseLiveTest.Config.class, UserAvatarDatabaseLiveTest.Config.class})
@EnabledIfEnvironmentVariable(named = "CATGAL_AVATAR_DB_TEST", matches = "1")
class UserAvatarDatabaseLiveTest {
    @Autowired private IStorageFileService files;
    @Autowired private IObjectStorageService objects;
    @Autowired @Qualifier("avatarTestUsers") private UserMapper users;
    @Autowired private JdbcTemplate jdbc;
    private Long owner;
    private Long other;
    private MockMultipartFile file;
    private final String oldUrl = "https://assets.example/old-avatar.png";

    @BeforeEach
    void setup() throws Exception {
        reset(objects, users);
        String prefix = "av" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        owner = createUser(prefix);
        other = createUser(prefix + "b");
        UserContext.setUser(UserInfo.builder().userId(owner).build());
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1,
                java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", png);
        file = new MockMultipartFile("file", "avatar.png", "image/png", png.toByteArray());
    }

    @AfterEach
    void clear() {
        UserContext.clear();
        jdbc.update("UPDATE user SET avatar_file_id=NULL WHERE id IN (?,?)", owner, other);
        jdbc.update("DELETE FROM storage_file WHERE owner_id IN (?,?)", owner, other);
        jdbc.update("DELETE FROM user WHERE id IN (?,?)", owner, other);
    }

    private Long createUser(String name) {
        jdbc.update("INSERT INTO user(username,password,role,avatar_url) VALUES (?, 'isolated-no-login', 0, ?)", name, oldUrl);
        return jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, name);
    }

    private String avatar(Long id) {
        return jdbc.queryForObject("SELECT avatar_url FROM user WHERE id=?", String.class, id);
    }

    private String fileId() {
        return jdbc.queryForObject("SELECT id FROM storage_file WHERE owner_id=? ORDER BY create_time DESC LIMIT 1", String.class, owner);
    }

    @Test
    void avatarUpdateOnlyChangesTheCurrentAccount() {
        String saved = files.updateAvatar(file);
        String id = fileId();
        assertEquals(FileAccessPaths.avatar(id), saved);
        assertEquals(id, jdbc.queryForObject("SELECT avatar_file_id FROM user WHERE id=?", String.class, owner));
        assertNull(avatar(owner));
        assertEquals(oldUrl, avatar(other));
        assertNull(jdbc.queryForObject("SELECT avatar_file_id FROM user WHERE id=?", String.class, other));
        assertEquals("READY", files.getById(id).getStatus());
        verify(objects, never()).delete(anyString(), anyString());
    }

    @Test
    void databaseConstraintFailureCompensatesNewObjectAndKeepsOldAvatar() {
        // Force a real FK violation inside the final avatar transaction after the object upload.
        doAnswer(call -> {
            jdbc.update("UPDATE user SET avatar_file_id=? WHERE id=?", "f".repeat(32), owner);
            return 0;
        }).when(users).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        assertEquals(500, assertThrows(BusinessException.class, () -> files.updateAvatar(file)).getHttpStatus());
        assertEquals(oldUrl, avatar(owner));
        assertEquals(oldUrl, avatar(other));
        assertNull(jdbc.queryForObject("SELECT avatar_file_id FROM user WHERE id=?", String.class, owner));
        StorageFile stored = files.getById(fileId());
        assertEquals("DELETED", stored.getStatus());
        verify(objects).delete(stored.getBucket(), stored.getObjectKey());
    }

    @Test
    void removedAccountCannotCreateAnObject() {
        jdbc.update("DELETE FROM user WHERE id=?", owner);
        assertEquals(404, assertThrows(BusinessException.class, () -> files.updateAvatar(file)).getHttpStatus());
        verifyNoInteractions(objects);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file WHERE owner_id=?", Long.class, owner));
    }

    @Test
    void databaseEnforcesAvatarOwnershipAndUserDeletionConstraints() {
        files.updateAvatar(file);
        String id = fileId();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE user SET avatar_file_id=? WHERE id=?", id, other));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM user WHERE id=?", owner));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM storage_file WHERE id=?", id));
    }

    @Test
    void cleanupFailurePersistsRetryAndProtectsCurrentAvatar() {
        String first = files.updateAvatar(file);
        String firstId = fileId();
        String second = files.updateAvatar(file);
        String currentId = fileId();
        assertNotEquals(first, second);
        assertEquals("CLEANUP_PENDING", files.getById(firstId).getStatus());
        StorageFile old = files.getById(firstId);
        doThrow(new IllegalStateException("storage unavailable")).when(objects).delete(old.getBucket(), old.getObjectKey());
        files.cleanup(firstId);
        assertEquals("CLEANUP_PENDING", files.getById(firstId).getStatus());
        assertEquals(1, files.getById(firstId).getCleanupAttempts());
        jdbc.update("UPDATE storage_file SET next_attempt_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", firstId);
        doNothing().when(objects).delete(old.getBucket(), old.getObjectKey());
        files.cleanup(firstId);
        assertEquals("DELETED", files.getById(firstId).getStatus());
        files.cleanup(currentId);
        StorageFile current = files.getById(currentId);
        verify(objects, never()).delete(current.getBucket(), current.getObjectKey());
        assertEquals("READY", current.getStatus());
    }

    @Test
    void concurrentUploadsKeepExactlyOneReadyAvatarAndUniqueKeys() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                tasks.add(() -> {
                    UserContext.setUser(UserInfo.builder().userId(owner).build());
                    try { return files.updateAvatar(file); } finally { UserContext.clear(); }
                });
            }
            List<String> paths = new ArrayList<>();
            for (var future : executor.invokeAll(tasks)) paths.add(future.get());
            assertEquals(4, new java.util.HashSet<>(paths).size());
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file WHERE owner_id=? AND status='READY'", Long.class, owner));
            assertEquals(3L, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file WHERE owner_id=? AND status='CLEANUP_PENDING'", Long.class, owner));
            assertNull(avatar(owner));
            assertEquals(oldUrl, avatar(other));
        } finally {
            executor.shutdownNow();
        }
    }

    @Configuration
    static class Config {
        @Bean IObjectStorageService objects() { return mock(IObjectStorageService.class); }
        @Bean("avatarTestUsers") UserMapper decorated(@Qualifier("userMapper") UserMapper original) { return spy(original); }
        @Bean IStorageFileService files(IObjectStorageService objects, @Qualifier("avatarTestUsers") UserMapper users,
                                       PlatformTransactionManager manager, StorageFileMapper metadata) {
            StorageProperties properties = new StorageProperties();
            properties.setEnabled(true);
            return new StorageFileServiceImpl(objects, new CurrentUserProvider(), properties, new AvatarImageValidator(), manager,
                    List.of(new com.catgal.server.service.impl.AvatarFileBindingServiceImpl(users, metadata)));
        }
    }
}
