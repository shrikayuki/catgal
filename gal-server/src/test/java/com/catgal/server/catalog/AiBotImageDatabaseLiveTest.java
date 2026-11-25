package com.catgal.server.catalog;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.mapper.AiBotMapper;
import com.catgal.server.mapper.StorageFileMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IObjectStorageService;
import com.catgal.server.service.IStorageFileService;
import com.catgal.server.service.impl.AiBotImageFileBindingServiceImpl;
import com.catgal.server.service.impl.StorageFileServiceImpl;
import com.catgal.server.storage.AiBotImageKind;
import com.catgal.server.storage.AvatarImageValidator;
import com.catgal.server.storage.StorageProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig({CatalogDatabaseLiveTest.Config.class, AiBotImageDatabaseLiveTest.Config.class})
@EnabledIfEnvironmentVariable(named = "CATGAL_AI_BOT_IMAGE_DB_TEST", matches = "1")
class AiBotImageDatabaseLiveTest {
    @Autowired private IStorageFileService files;
    @Autowired private IObjectStorageService objects;
    @Autowired private JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, byte[]> content = new ConcurrentHashMap<>();
    private long owner;
    private long stranger;
    private long credential;
    private long bot;
    private String prefix;
    private MockMultipartFile image;

    @BeforeEach void setup() throws Exception {
        reset(objects);
        prefix = "bi" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO user(username,password,role) VALUES (?, 'isolated-no-login', 0)", prefix);
        owner = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, prefix);
        jdbc.update("INSERT INTO user(username,password,role) VALUES (?, 'isolated-no-login', 0)", prefix + "x");
        stranger = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, prefix + "x");
        Long template = jdbc.queryForObject("SELECT MIN(id) FROM ai_girl", Long.class);
        assertNotNull(template);
        jdbc.update("INSERT INTO ai_user_credential(user_id,provider_code,label,masked_suffix,status,revision,create_time,update_time) "
                + "VALUES (?,'dashscope',?,'0000',0,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", owner, prefix);
        credential = jdbc.queryForObject("SELECT id FROM ai_user_credential WHERE user_id=? AND label=?", Long.class, owner, prefix);
        jdbc.update("INSERT INTO ai_bot(user_id,template_id,kind,name,credential_id,model,revision,status,create_time,update_time) "
                + "VALUES (?,?,'custom',?,?,'isolated-model',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", owner, template, prefix, credential);
        bot = jdbc.queryForObject("SELECT id FROM ai_bot WHERE user_id=? AND name=?", Long.class, owner, prefix);
        login(owner);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        image = new MockMultipartFile("file", "local.png", "image/png", bytes.toByteArray());
        doAnswer(call -> {
            content.put(call.getArgument(1), ((InputStream) call.getArgument(2)).readAllBytes());
            return null;
        }).when(objects).put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
        when(objects.open(anyString(), anyString())).thenAnswer(call -> new ByteArrayInputStream(content.get(call.getArgument(1))));
        doAnswer(call -> { content.remove(call.getArgument(1)); return null; }).when(objects).delete(anyString(), anyString());
    }

    @AfterEach void cleanup() {
        UserContext.clear();
        // 只清理本测试创建的精确账号和业务引用，避免触碰共享数据库中的其他任务。
        jdbc.update("DELETE FROM storage_file WHERE owner_id=? AND purpose IN ('AI_BOT_AVATAR','AI_BOT_PORTRAIT') AND business_id=?", owner, bot);
        jdbc.update("DELETE FROM ai_bot WHERE id=? AND user_id=? AND name=?", bot, owner, prefix);
        jdbc.update("DELETE FROM ai_user_credential WHERE id=? AND user_id=? AND label=?", credential, owner, prefix);
        jdbc.update("DELETE FROM user WHERE id IN (?,?) AND username IN (?,?)", owner, stranger, prefix, prefix + "x");
    }

    private void login(long userId) { UserContext.setUser(UserInfo.builder().userId(userId).build()); }

    @Test void uploadsBothImagesWithIndependentReferencesAndPrivateReads() throws Exception {
        var avatar = files.updateAiBotImage(bot, AiBotImageKind.AVATAR, 1L, image);
        var portrait = files.updateAiBotImage(bot, AiBotImageKind.PORTRAIT, 2L, image);
        assertEquals(3L, portrait.revision());
        assertEquals(avatar.url(), jdbc.queryForObject("SELECT avatar_url FROM ai_bot WHERE id=?", String.class, bot));
        assertEquals(portrait.url(), jdbc.queryForObject("SELECT portrait_url FROM ai_bot WHERE id=?", String.class, bot));
        try (var stream = files.openAiBotImage(AiBotImageKind.AVATAR, avatar.fileId()).content()) {
            assertArrayEquals(image.getBytes(), stream.readAllBytes());
        }
        login(stranger);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> files.openAiBotImage(AiBotImageKind.AVATAR, avatar.fileId())).getHttpStatus());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> files.updateAiBotImage(bot, AiBotImageKind.PORTRAIT, 3L, image)).getHttpStatus());
        UserContext.clear();
        assertEquals(401, assertThrows(BusinessException.class,
                () -> files.openAiBotImage(AiBotImageKind.AVATAR, avatar.fileId())).getHttpStatus());
    }

    @Test void replacementAndRemovalRetireOnlyThePreviousManagedObject() {
        var first = files.updateAiBotImage(bot, AiBotImageKind.AVATAR, 1L, image);
        var second = files.updateAiBotImage(bot, AiBotImageKind.AVATAR, 2L, image);
        assertEquals("CLEANUP_PENDING", files.getById(first.fileId()).getStatus());
        files.cleanup(first.fileId());
        assertEquals("DELETED", files.getById(first.fileId()).getStatus());
        assertEquals("READY", files.getById(second.fileId()).getStatus());
        assertEquals(4L, files.clearAiBotImage(bot, AiBotImageKind.AVATAR, 3L).revision());
        assertEquals("", jdbc.queryForObject("SELECT avatar_url FROM ai_bot WHERE id=?", String.class, bot));
        assertEquals("CLEANUP_PENDING", files.getById(second.fileId()).getStatus());
    }

    @Test void concurrentUploadsCommitOneImageAndCompensateTheLoser() throws Exception {
        CyclicBarrier uploaded = new CyclicBarrier(2);
        doAnswer(call -> {
            content.put(call.getArgument(1), ((InputStream) call.getArgument(2)).readAllBytes());
            uploaded.await(10, TimeUnit.SECONDS);
            return null;
        }).when(objects).put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> upload = () -> {
                login(owner);
                try { files.updateAiBotImage(bot, AiBotImageKind.AVATAR, 1L, image); return 200; }
                catch (BusinessException error) { return error.getHttpStatus(); }
                finally { UserContext.clear(); }
            };
            var results = executor.invokeAll(List.of(upload, upload));
            assertEquals(List.of(200, 409), results.stream().map(result -> {
                try { return result.get(); } catch (Exception error) { throw new AssertionError(error); }
            }).sorted().toList());
            assertEquals(2L, jdbc.queryForObject("SELECT revision FROM ai_bot WHERE id=?", Long.class, bot));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file WHERE business_id=? AND owner_id=? AND status='READY'", Long.class, bot, owner));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file WHERE business_id=? AND owner_id=? AND status='DELETED'", Long.class, bot, owner));
            assertEquals(1, content.size());
        } finally { executor.shutdownNow(); }
    }

    @Test void storageFailureKeepsTheOldReferenceAndPersistsCleanup() {
        doThrow(new IllegalStateException("isolated failure")).when(objects)
                .put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
        assertEquals(500, assertThrows(BusinessException.class,
                () -> files.updateAiBotImage(bot, AiBotImageKind.AVATAR, 1L, image)).getHttpStatus());
        assertEquals(1L, jdbc.queryForObject("SELECT revision FROM ai_bot WHERE id=?", Long.class, bot));
        assertEquals("", jdbc.queryForObject("SELECT avatar_url FROM ai_bot WHERE id=?", String.class, bot));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file WHERE business_id=? AND owner_id=? AND status='CLEANUP_PENDING'", Long.class, bot, owner));
    }

    @Test void archivingDuringUploadPreventsBindingAndCleansTheUploadedObject() {
        doAnswer(call -> {
            content.put(call.getArgument(1), ((InputStream) call.getArgument(2)).readAllBytes());
            jdbc.update("UPDATE ai_bot SET status=0,revision=revision+1 WHERE id=? AND user_id=?", bot, owner);
            return null;
        }).when(objects).put(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> files.updateAiBotImage(bot, AiBotImageKind.AVATAR, 1L, image)).getHttpStatus());
        assertEquals("", jdbc.queryForObject("SELECT avatar_url FROM ai_bot WHERE id=?", String.class, bot));
        assertTrue(content.isEmpty());
    }

    @Configuration static class Config {
        @Bean IObjectStorageService objects() { return mock(IObjectStorageService.class); }
        @Bean IStorageFileService files(IObjectStorageService objects, AiBotMapper bots,
                                       StorageFileMapper metadata, PlatformTransactionManager manager) {
            StorageProperties properties = new StorageProperties();
            properties.setEnabled(true);
            CurrentUserProvider current = new CurrentUserProvider();
            return new StorageFileServiceImpl(objects, current, properties, new AvatarImageValidator(), manager,
                    List.of(new AiBotImageFileBindingServiceImpl(AiBotImageKind.AVATAR, bots, metadata, current),
                            new AiBotImageFileBindingServiceImpl(AiBotImageKind.PORTRAIT, bots, metadata, current)));
        }
    }
}
