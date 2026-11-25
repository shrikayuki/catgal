package com.catgal.server.catalog;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.po.StorageFile;
import com.catgal.server.domain.po.User;
import com.catgal.server.domain.dto.admin.GameCatalogUpdateDTO;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.StorageFileMapper;
import com.catgal.server.mapper.UserMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.IObjectStorageService;
import com.catgal.server.service.IStorageFileService;
import com.catgal.server.service.impl.AvatarFileBindingServiceImpl;
import com.catgal.server.service.impl.GameCoverFileBindingServiceImpl;
import com.catgal.server.service.impl.StorageFileServiceImpl;
import com.catgal.server.storage.AvatarImageValidator;
import com.catgal.server.storage.StorageProperties;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig({CatalogDatabaseLiveTest.Config.class, GameCoverDatabaseLiveTest.Config.class})
@EnabledIfEnvironmentVariable(named = "CATGAL_GAME_COVER_DB_TEST", matches = "1")
class GameCoverDatabaseLiveTest {
    @Autowired private IStorageFileService files;
    @Autowired private IObjectStorageService objects;
    @Autowired @Qualifier("coverTestUsers") private UserMapper users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private GameCatalogService catalog;
    private long admin;
    private long ordinary;
    private long game;
    private long otherGame;
    private String prefix;
    private MockMultipartFile image;

    @BeforeEach void setup() throws Exception {
        reset(objects, users);
        admin = jdbc.queryForObject("SELECT MIN(id) FROM user WHERE role=2", Long.class);
        prefix = "cc" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.update("INSERT INTO user(username,password,role) VALUES (?, 'isolated-no-login', 0)", prefix);
        ordinary = jdbc.queryForObject("SELECT id FROM user WHERE username=?", Long.class, prefix);
        game = createGame(prefix + "a");
        otherGame = createGame(prefix + "b");
        login(admin);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", bytes);
        image = new MockMultipartFile("file", "cover.png", "image/png", bytes.toByteArray());
    }

    @AfterEach void cleanup() {
        UserContext.clear();
        jdbc.update("UPDATE game SET cover_file_id=NULL WHERE id IN (?,?)", game, otherGame);
        jdbc.update("DELETE FROM storage_file WHERE purpose='GAME_COVER' AND business_id IN (?,?)", game, otherGame);
        jdbc.update("DELETE FROM game WHERE id IN (?,?) AND name LIKE ?", game, otherGame, prefix + "%");
        jdbc.update("DELETE FROM user WHERE id=? AND username=?", ordinary, prefix);
    }

    private long createGame(String name) {
        jdbc.update("INSERT INTO game(name,publisher_id,status,revision,cover_url,create_time,update_time) "
                + "VALUES (?,?,0,0,'https://assets.example/legacy-cover.png',NOW(6),NOW(6))", name, admin);
        return jdbc.queryForObject("SELECT id FROM game WHERE name=?", Long.class, name);
    }

    private void login(long id) { UserContext.setUser(UserInfo.builder().userId(id).role(2).build()); }

    @Test void newCoverUsesAScopedReferenceAndClearsTheLegacyUrl() {
        var result = files.updateGameCover(game, 0L, image);
        assertEquals(1L, result.revision());
        StorageFile stored = files.getById(result.fileId());
        assertEquals("GAME_COVER", stored.getPurpose());
        assertEquals(game, stored.getBusinessId());
        assertEquals(admin, stored.getOwnerId());
        assertTrue(stored.getObjectKey().matches("v1/game-covers/[0-9]{4}/[0-9]{2}/[0-9]{2}/" + game + "/[0-9a-f]{32}\\.png"));
        assertNull(jdbc.queryForObject("SELECT cover_url FROM game WHERE id=?", String.class, game));
        assertEquals(result.fileId(), jdbc.queryForObject("SELECT cover_file_id FROM game WHERE id=?", String.class, game));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE game SET cover_file_id=? WHERE id=?", result.fileId(), otherGame));
        assertNull(jdbc.queryForObject("SELECT cover_file_id FROM game WHERE id=?", String.class, otherGame));
    }

    @Test void draftAndOfflineImagesRequireTheCurrentDatabaseAdministratorRole() throws Exception {
        var result = files.updateGameCover(game, 0L, image);
        when(objects.open(anyString(), anyString())).thenAnswer(call -> new java.io.ByteArrayInputStream(image.getBytes()));
        try (var content = files.openGameCover(result.fileId()).content()) { assertArrayEquals(image.getBytes(), content.readAllBytes()); }
        UserContext.clear();
        assertEquals(404, assertThrows(BusinessException.class, () -> files.openGameCover(result.fileId())).getHttpStatus());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("UPDATE game SET status=1 WHERE id=?", game);
            try (var content = files.openGameCover(result.fileId()).content()) {
                assertNotNull(content);
            } catch (java.io.IOException exception) { throw new AssertionError(exception); }
            status.setRollbackOnly();
        });
        jdbc.update("UPDATE game SET status=2 WHERE id=?", game);
        login(ordinary);
        assertEquals(404, assertThrows(BusinessException.class, () -> files.openGameCover(result.fileId())).getHttpStatus());
    }

    @Test void ordinaryCallersAndStaleRevisionsCannotCreateObjects() {
        login(ordinary);
        assertEquals(403, assertThrows(BusinessException.class, () -> files.updateGameCover(game, 0L, image)).getHttpStatus());
        login(admin);
        assertEquals(409, assertThrows(BusinessException.class, () -> files.updateGameCover(game, 5L, image)).getHttpStatus());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file WHERE purpose='GAME_COVER' AND business_id=?", Long.class, game));
        verifyNoInteractions(objects);
    }

    @Test void normalCatalogEditsPreserveTheManagedReferenceAndRejectAnUnrelatedUrl() {
        var result = files.updateGameCover(game, 0L, image);
        var before = catalog.detail(game).game();
        assertEquals(game, before.getId());
        assertEquals(prefix + "a", before.getName());
        assertEquals(result.url(), before.getCoverUrl());
        GameCatalogUpdateDTO change = new GameCatalogUpdateDTO();
        change.setName(prefix + "edited");
        change.setRevision(1L);
        change.setCoverUrl(result.url());
        var updated = catalog.update(game, change).game();
        assertEquals(2L, updated.getRevision());
        assertEquals(result.fileId(), updated.getCoverFileId());
        assertEquals(result.url(), updated.getCoverUrl());
        assertNull(jdbc.queryForObject("SELECT cover_url FROM game WHERE id=?", String.class, game));
        change.setRevision(2L);
        change.setCoverUrl("https://assets.example/unrelated.png");
        assertEquals(400, assertThrows(BusinessException.class, () -> catalog.update(game, change)).getHttpStatus());
        assertEquals(2L, catalog.detail(game).game().getRevision());
    }

    @Test void replacingAndClearingOnlyRetiresSupersededObjects() {
        var first = files.updateGameCover(game, 0L, image);
        var second = files.updateGameCover(game, 1L, image);
        assertEquals("CLEANUP_PENDING", files.getById(first.fileId()).getStatus());
        assertEquals(404, assertThrows(BusinessException.class, () -> files.openGameCover(first.fileId())).getHttpStatus());
        files.cleanup(first.fileId());
        assertEquals("DELETED", files.getById(first.fileId()).getStatus());
        files.cleanup(second.fileId());
        StorageFile current = files.getById(second.fileId());
        verify(objects, never()).delete(current.getBucket(), current.getObjectKey());
        assertEquals(3L, files.clearGameCover(game, 2L).revision());
        assertNull(jdbc.queryForObject("SELECT cover_file_id FROM game WHERE id=?", String.class, game));
        assertEquals("CLEANUP_PENDING", files.getById(second.fileId()).getStatus());
    }

    @Test void concurrentUploadsCommitOneRevisionAndCompensateTheLoser() throws Exception {
        CyclicBarrier uploaded = new CyclicBarrier(2);
        doAnswer(call -> { uploaded.await(10, TimeUnit.SECONDS); return null; })
                .when(objects).put(anyString(), anyString(), any(java.io.InputStream.class), anyLong(), anyString());
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> request = () -> {
                login(admin);
                try { files.updateGameCover(game, 0L, image); return 200; }
                catch (BusinessException exception) { return exception.getHttpStatus(); }
                finally { UserContext.clear(); }
            };
            var results = executor.invokeAll(List.of(request, request));
            assertEquals(List.of(200, 409), results.stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).sorted().toList());
            assertEquals(1L, jdbc.queryForObject("SELECT revision FROM game WHERE id=?", Long.class, game));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file WHERE purpose='GAME_COVER' AND business_id=? AND status='READY'", Long.class, game));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file WHERE purpose='GAME_COVER' AND business_id=? AND status='DELETED'", Long.class, game));
        } finally { executor.shutdownNow(); }
    }

    @Test void physicalGameDeletionPersistsAnObjectCleanupTask() {
        var result = files.updateGameCover(game, 0L, image);
        jdbc.update("DELETE FROM game WHERE id=? AND name=?", game, prefix + "a");
        assertEquals("CLEANUP_PENDING", files.getById(result.fileId()).getStatus());
        files.cleanup(result.fileId());
        assertEquals("DELETED", files.getById(result.fileId()).getStatus());
    }

    @Test void administratorRevocationDuringUploadPreventsBinding() {
        doAnswer(call -> {
            User downgraded = new User();
            downgraded.setId(admin);
            downgraded.setRole(0);
            doReturn(downgraded).when(users).selectById(admin);
            return null;
        }).when(objects).put(anyString(), anyString(), any(java.io.InputStream.class), anyLong(), anyString());
        assertEquals(403, assertThrows(BusinessException.class, () -> files.updateGameCover(game, 0L, image)).getHttpStatus());
        assertNull(jdbc.queryForObject("SELECT cover_file_id FROM game WHERE id=?", String.class, game));
        assertEquals(2, jdbc.queryForObject("SELECT role FROM user WHERE id=?", Integer.class, admin));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM storage_file WHERE business_id=? AND purpose='GAME_COVER' AND status='DELETED'", Long.class, game));
    }

    @Configuration static class Config {
        @Bean IObjectStorageService objects() { return mock(IObjectStorageService.class); }
        @Bean("coverTestUsers") UserMapper users(@Qualifier("userMapper") UserMapper source) { return spy(source); }
        @Bean IStorageFileService files(IObjectStorageService objects, @Qualifier("coverTestUsers") UserMapper users,
                                       GameMapper games, StorageFileMapper metadata, PlatformTransactionManager manager) {
            StorageProperties properties = new StorageProperties();
            properties.setEnabled(true);
            CurrentUserProvider current = new CurrentUserProvider();
            return new StorageFileServiceImpl(objects, current, properties, new AvatarImageValidator(), manager,
                    List.of(new AvatarFileBindingServiceImpl(users, metadata), new GameCoverFileBindingServiceImpl(games, users, metadata, current)));
        }
    }
}
