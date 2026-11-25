package com.catgal.server.ai;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.catgal.common.domain.query.GamePageQuery;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.ai.vector.AiVectorCatalogRequests;
import com.catgal.server.ai.vector.AiVectorCatalogService;
import com.catgal.server.domain.po.Game;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.search.MysqlGameSearch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.mybatis.spring.SqlSessionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MySQL tests. All temporary catalog rows and every trigger/outbox write roll back. */
@SpringJUnitConfig(AiVectorCatalogDatabaseLiveTest.Config.class)
@EnabledIfEnvironmentVariable(named = "CATGAL_AI_VECTOR_DB_TEST", matches = "1")
@Transactional
class AiVectorCatalogDatabaseLiveTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private GameMapper mapper;
    @Autowired private AiVectorCatalogService catalog;
    @Autowired private MysqlGameSearch search;
    @Autowired private SqlSessionFactory sessions;

    private Game fixture(int status) {
        Long publisher = jdbc.queryForObject("SELECT id FROM user ORDER BY id LIMIT 1", Long.class);
        Game game = new Game().setName("codex-vector-" + UUID.randomUUID()).setStatus(status).setPublisherId(publisher)
                .setIntroduction("fixture").setReleaseDate(LocalDate.of(2020, 3, 1))
                .setLanguages("中文,日语").setPlatforms("win,linux").setTypes("汉化");
        mapper.insert(game);
        return game;
    }

    private long version(long id) {
        return jdbc.queryForObject("SELECT version FROM ai_game_vector_outbox WHERE game_id=?", Long.class, id);
    }

    @Test void exportsBoundedPublicUnicodeDocumentAndRetainsTombstones() throws Exception {
        Game game = fixture(1);
        String emoji = new String(Character.toChars(0x1F43E));
        jdbc.update("UPDATE game SET introduction=?,cover_url=? WHERE id=?", emoji.repeat(6001), "private-url", game.getId());
        var item = catalog.export(List.of(game.getId())).items().get(0);
        assertEquals(String.valueOf(version(game.getId())), item.version());
        assertFalse(item.deleted());
        assertEquals(6000, item.game().introduction().codePointCount(0, item.game().introduction().length()));
        assertEquals(List.of("中文", "日语"), item.game().languages());
        assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(item).contains("private-url"));
        long previous = version(game.getId());
        jdbc.update("UPDATE game SET status=2 WHERE id=?", game.getId());
        var hidden = catalog.export(List.of(game.getId())).items().get(0);
        assertTrue(hidden.deleted());
        assertNull(hidden.game());
        assertTrue(Long.parseLong(hidden.version()) > previous);
        jdbc.update("DELETE FROM game WHERE id=?", game.getId());
        var tombstone = catalog.export(List.of(game.getId())).items().get(0);
        assertTrue(tombstone.deleted());
        assertTrue(Long.parseLong(tombstone.version()) > Long.parseLong(hidden.version()));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> catalog.export(List.of(Long.MAX_VALUE))).getHttpStatus());
    }

    @Test void staleAckFailureAndRebuildNeverConsumeNewerOrOrdinarySearchEvents() {
        Game game = fixture(1);
        long first = version(game.getId());
        jdbc.update("UPDATE game SET name=CONCAT(name,'-changed') WHERE id=?", game.getId());
        var old = new AiVectorCatalogRequests.Version(game.getId(), first);
        assertEquals(1, catalog.acknowledge(List.of(old)).stale());
        assertEquals(1, catalog.failed(List.of(old), AiVectorCatalogRequests.FailureCode.ES_UNAVAILABLE).stale());
        long current = version(game.getId());
        var event = new AiVectorCatalogRequests.Version(game.getId(), current);
        assertEquals(1, catalog.failed(List.of(event), AiVectorCatalogRequests.FailureCode.EMBEDDING_UNAVAILABLE).updated());
        assertEquals("EMBEDDING_UNAVAILABLE", jdbc.queryForObject(
                "SELECT last_error_code FROM ai_game_vector_outbox WHERE game_id=?", String.class, game.getId()));
        assertEquals(1, catalog.acknowledge(List.of(event)).acknowledged());
        assertEquals(1, jdbc.queryForObject("SELECT pending FROM search_outbox WHERE game_id=?", Integer.class, game.getId()));
        catalog.rebuild();
        assertTrue(version(game.getId()) > current);
        assertEquals(1, catalog.acknowledge(List.of(event)).stale());
        assertEquals(1, jdbc.queryForObject("SELECT pending FROM ai_game_vector_outbox WHERE game_id=?", Integer.class, game.getId()));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> catalog.acknowledge(List.of(event, event))).getHttpStatus());
    }

    @Test void sourceVersionIgnoresHotCountersButTracksDictionaryAndRelationChanges() {
        Game game = fixture(1);
        long original = version(game.getId());
        jdbc.update("UPDATE game SET view_count=COALESCE(view_count,0)+1,rating=9.5,cover_url='safe-fixture' WHERE id=?", game.getId());
        assertEquals(original, version(game.getId()));
        String name = "codex-vtag-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO tag(name) VALUES(?)", name);
        long tag = jdbc.queryForObject("SELECT id FROM tag WHERE name=?", Long.class, name);
        jdbc.update("INSERT INTO game_tag(game_id,tag_id) VALUES(?,?)", game.getId(), tag);
        long linked = version(game.getId());
        assertTrue(linked > original);
        jdbc.update("UPDATE tag SET name=CONCAT(name,'-renamed') WHERE id=?", tag);
        assertTrue(version(game.getId()) > linked);
        assertTrue(catalog.export(List.of(game.getId())).items().get(0).game().tags().get(0).endsWith("-renamed"));
        long renamed = version(game.getId());
        jdbc.update("DELETE FROM game_tag WHERE game_id=? AND tag_id=?", game.getId(), tag);
        assertTrue(version(game.getId()) > renamed);
    }

    @Test void semanticCandidatesRequireExactVersionPublishedStatusAndEveryTraditionalFilter() {
        Game game = fixture(1);
        Game draft = fixture(0);
        String suffix = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tag(name) VALUES(?)", "codex-vtag-" + suffix);
        jdbc.update("INSERT INTO company(name) VALUES(?)", "codex-vcompany-" + suffix);
        Integer tag = jdbc.queryForObject("SELECT id FROM tag WHERE name=?", Integer.class, "codex-vtag-" + suffix);
        Long company = jdbc.queryForObject("SELECT id FROM company WHERE name=?", Long.class, "codex-vcompany-" + suffix);
        jdbc.update("INSERT INTO game_tag(game_id,tag_id) VALUES(?,?)", game.getId(), tag);
        jdbc.update("INSERT INTO game_company(game_id,company_id) VALUES(?,?)", game.getId(), company);
        long current = version(game.getId());
        Map<Long, Long> candidates = Map.of(game.getId(), current, draft.getId(), version(draft.getId()));
        GamePageQuery filters = new GamePageQuery();
        filters.setTagId(tag); filters.setCompanyId(company); filters.setReleaseYear(2020);
        filters.setLanguage("中文"); filters.setPlatform("win"); filters.setResourceType("汉化");
        assertEquals(List.of(game.getId()), search.filterCurrentCandidates(candidates, filters));
        filters.setTagId(tag + 1); assertTrue(search.filterCurrentCandidates(candidates, filters).isEmpty()); filters.setTagId(tag);
        filters.setCompanyId(company + 1); assertTrue(search.filterCurrentCandidates(candidates, filters).isEmpty()); filters.setCompanyId(company);
        filters.setReleaseYear(2021); assertTrue(search.filterCurrentCandidates(candidates, filters).isEmpty()); filters.setReleaseYear(2020);
        filters.setLanguage("英语"); assertTrue(search.filterCurrentCandidates(candidates, filters).isEmpty()); filters.setLanguage("中文");
        filters.setPlatform("android"); assertTrue(search.filterCurrentCandidates(candidates, filters).isEmpty()); filters.setPlatform("win");
        filters.setResourceType("补丁"); assertTrue(search.filterCurrentCandidates(candidates, filters).isEmpty()); filters.setResourceType("汉化");
        jdbc.update("UPDATE game SET introduction='new semantic source' WHERE id=?", game.getId());
        // Separate HTTP validations use separate SqlSessions. Clear this test transaction's earlier SELECT cache.
        var session = SqlSessionUtils.getSqlSession(sessions);
        session.clearCache();
        SqlSessionUtils.closeSqlSession(session, sessions);
        assertTrue(search.filterCurrentCandidates(candidates, filters).isEmpty());
        assertEquals(List.of(game.getId()), search.filterCurrentCandidates(Map.of(game.getId(), version(game.getId())), filters));
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("MYSQL_URL"), System.getenv("MYSQL_USERNAME"), System.getenv("MYSQL_PASSWORD"));
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean AiVectorCatalogService catalog(JdbcTemplate jdbc) { return new AiVectorCatalogService(jdbc); }
        @Bean MysqlGameSearch search(GameMapper mapper) { return new MysqlGameSearch(mapper); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(configuration); factory.setDataSource(source);
            return factory.getObject();
        }
        @Bean MapperFactoryBean<GameMapper> gameMapper(SqlSessionFactory factory) {
            MapperFactoryBean<GameMapper> mapper = new MapperFactoryBean<>(GameMapper.class);
            mapper.setSqlSessionFactory(factory);
            return mapper;
        }
    }
}
