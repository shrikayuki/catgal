package com.catgal.server.catalog;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.exception.BusinessException;
import com.catgal.server.domain.dto.admin.CatalogRelationsUpdateDTO;
import com.catgal.server.domain.dto.admin.GameCatalogCreateDTO;
import com.catgal.server.domain.dto.admin.GameCatalogUpdateDTO;
import com.catgal.server.domain.vo.admin.AdminGameDetailVO;
import com.catgal.server.domain.query.CatalogPageQuery;
import com.catgal.server.mapper.GameMapper;
import com.catgal.server.mapper.CompanyMapper;
import com.catgal.server.mapper.TagMapper;
import com.catgal.server.mapper.GameCompanyMapper;
import com.catgal.server.mapper.GameTagMapper;
import com.catgal.server.service.ICompanyService;
import com.catgal.server.service.ITagService;
import com.catgal.server.service.impl.CompanyServiceImpl;
import com.catgal.server.service.impl.TagServiceImpl;
import com.catgal.server.catalog.external.ExternalGamePreview;
import com.catgal.server.catalog.external.ExternalLinkStore;
import com.catgal.server.catalog.external.ExternalSource;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Uses isolated service wiring. Each test rolls back its temporary catalog rows and trigger writes. */
@SpringJUnitConfig(CatalogDatabaseLiveTest.Config.class)
@EnabledIfEnvironmentVariable(named = "CATGAL_CATALOG_DB_TEST", matches = "1")
@Transactional
class CatalogDatabaseLiveTest {
    @Autowired private ICompanyService companies;
    @Autowired private ITagService tags;
    @Autowired private GameCatalogService games;
    @Autowired private GameMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExternalLinkStore externalLinks;
    private String prefix;

    @BeforeEach
    void setup() {
        prefix = "codex-test-" + UUID.randomUUID().toString().substring(0, 12);
        UserContext.setUser(UserInfo.builder().userId(6L).role(2).build());
    }

    @AfterEach
    void clear() { UserContext.clear(); }

    private AdminGameDetailVO fixture() {
        var company = companies.createCatalog(prefix + "-company");
        var tag = tags.createCatalog(prefix + "-tag");
        GameCatalogCreateDTO dto = new GameCatalogCreateDTO();
        dto.setName(prefix + "-game");
        dto.setCompanyIds(Set.of(company.id()));
        dto.setTagIds(Set.of(tag.id().intValue()));
        return games.create(dto);
    }

    @Test
    void createsEditsReplacesRelationsAndDeletesUnreferencedEntries() {
        AdminGameDetailVO created = fixture();
        assertEquals(0, created.game().getStatus());
        assertEquals(1, created.tags().size());
        assertEquals(1, created.companies().size());
        GameCatalogUpdateDTO update = new GameCatalogUpdateDTO();
        update.setName(prefix + "-edited");
        update.setRevision(created.game().getRevision());
        update.setCompanyIds(Set.of(created.companies().get(0).id()));
        update.setTagIds(Set.of(created.tags().get(0).id().intValue()));
        var changed = games.update(created.game().getId(), update);
        assertEquals(1L, changed.game().getRevision());
        CatalogRelationsUpdateDTO edges = new CatalogRelationsUpdateDTO();
        edges.setRevision(changed.game().getRevision());
        edges.setIds(Set.of());
        changed = games.replaceRelations(CatalogKind.COMPANY, created.game().getId(), edges);
        assertTrue(changed.companies().isEmpty());
        companies.deleteCatalog(created.companies().get(0).id(), 0L);
        edges.setRevision(changed.game().getRevision());
        changed = games.replaceRelations(CatalogKind.TAG, created.game().getId(), edges);
        assertTrue(changed.tags().isEmpty());
        tags.deleteCatalog(created.tags().get(0).id(), 0L);
        assertTrue(jdbc.queryForObject("SELECT pending FROM search_outbox WHERE game_id = ?", Boolean.class,
                created.game().getId()));
    }

    @Test
    void staleGameEditIsRejectedAndPreservesNewerData() {
        AdminGameDetailVO created = fixture();
        GameCatalogUpdateDTO dto = new GameCatalogUpdateDTO();
        dto.setName(prefix + "-newer");
        dto.setRevision(0L);
        games.update(created.game().getId(), dto);
        dto.setName(prefix + "-stale");
        assertEquals(409, assertThrows(BusinessException.class,
                () -> games.update(created.game().getId(), dto)).getHttpStatus());
        assertEquals(prefix + "-newer", mapper.selectById(created.game().getId()).getName());
    }

    @Test
    void dictionaryCrudKeepsDefaultsPaginationRevisionsAndDuplicateProtection() {
        var company = companies.createCatalog("  " + prefix + "-company  ");
        var tag = tags.createCatalog("  " + prefix + "-tag  ");
        assertNotNull(company.createTime());
        assertNotNull(tag.createTime());
        assertEquals(0L, company.revision());
        assertEquals(0L, tag.revision());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> companies.createCatalog(prefix + "-company")).getHttpStatus());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> tags.createCatalog(prefix + "-tag")).getHttpStatus());

        var changedCompany = companies.updateCatalog(company.id(), prefix + "-renamed", 0L);
        var changedTag = tags.updateCatalog(tag.id(), prefix + "-renamed", 0L);
        assertEquals(1L, changedCompany.revision());
        assertEquals(1L, changedTag.revision());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> companies.updateCatalog(company.id(), prefix + "-stale", 0L)).getHttpStatus());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> tags.updateCatalog(tag.id(), prefix + "-stale", 0L)).getHttpStatus());

        CatalogPageQuery query = new CatalogPageQuery();
        query.setKeyword(prefix);
        query.setPageSize(1);
        assertEquals(1L, companies.pageCatalog(query).getTotal());
        assertEquals(1L, tags.pageCatalog(query).getTotal());
        assertEquals(company.id(), companies.pageCatalog(query).getList().get(0).id());
        assertEquals(tag.id(), tags.pageCatalog(query).getList().get(0).id());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> companies.deleteCatalog(company.id(), 0L)).getHttpStatus());
        assertEquals(409, assertThrows(BusinessException.class,
                () -> tags.deleteCatalog(tag.id(), 0L)).getHttpStatus());
        companies.deleteCatalog(company.id(), 1L);
        tags.deleteCatalog(tag.id(), 1L);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> companies.getCatalog(company.id())).getHttpStatus());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> tags.getCatalog(tag.id())).getHttpStatus());
    }

    @Test
    void databaseRejectsDeletingReferencedCompany() {
        AdminGameDetailVO created = fixture();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM company WHERE id = ?",
                created.companies().get(0).id()));
        assertEquals(1, games.detail(created.game().getId()).companies().size());
    }

    @Test
    void databaseRejectsOrphanRelationAndServiceRefusesReferencedTagDeletion() {
        AdminGameDetailVO created = fixture();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO game_tag (game_id, tag_id) VALUES (?, ?)", created.game().getId(), -1));
        assertEquals(409, assertThrows(BusinessException.class, () -> tags.deleteCatalog(
                created.tags().get(0).id(), 0L)).getHttpStatus());
    }

    private ExternalGamePreview externalItem(long gameId) {
        String id = "v" + (9_000_000_000L + gameId);
        return new ExternalGamePreview("vndb", id, "https://vndb.org/" + id, prefix + "-remote", null, null, null, "2002");
    }

    @Test
    void bindsReplacesAndUnbindsExternalReferencesWithGameRevisions() {
        var created = fixture();
        long gameId = created.game().getId();
        var first = externalLinks.bind(gameId, 0L, externalItem(gameId));
        assertEquals(1L, first.revision());
        assertEquals(1, first.links().size());
        var second = externalLinks.bind(gameId, 1L, new ExternalGamePreview("bangumi", "100", "https://bgm.tv/subject/100",
                prefix + "-bangumi", null, null, null, null));
        assertEquals(2, second.links().size());
        var updated = externalLinks.bind(gameId, 2L, externalItem(gameId + 1));
        assertEquals(3L, updated.revision());
        assertEquals(2, updated.links().size());
        var cleared = externalLinks.unbind(gameId, ExternalSource.VNDB, 3L);
        assertEquals(4L, cleared.revision());
        assertEquals(1, cleared.links().size());
        assertEquals(created.game().getName(), games.detail(gameId).game().getName());
    }

    @Test
    void rejectsDuplicateRemoteIdentityAcrossGames() {
        var first = fixture();
        prefix += "2";
        var second = fixture();
        var item = externalItem(first.game().getId());
        externalLinks.bind(first.game().getId(), 0L, item);
        assertEquals(409, assertThrows(BusinessException.class, () -> externalLinks.bind(second.game().getId(), 0L, item)).getHttpStatus());
        assertEquals(item.externalId(), externalLinks.adminLinks(first.game().getId()).links().get(0).externalId());
        assertTrue(externalLinks.adminLinks(second.game().getId()).links().isEmpty());
    }

    @Test
    void staleUnbindPreservesLinkAndPublicReadsRequirePublishedGame() {
        var created = fixture();
        long id = created.game().getId();
        externalLinks.bind(id, 0L, externalItem(id));
        assertEquals(409, assertThrows(BusinessException.class, () -> externalLinks.unbind(id, ExternalSource.VNDB, 0L)).getHttpStatus());
        assertEquals(404, assertThrows(BusinessException.class, () -> externalLinks.publicLinks(id)).getHttpStatus());
        games.setStatus(id, 1);
        assertEquals(1, externalLinks.publicLinks(id).size());
        games.setStatus(id, 2);
        assertEquals(404, assertThrows(BusinessException.class, () -> externalLinks.publicLinks(id)).getHttpStatus());
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = GameMapper.class)
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("MYSQL_URL"), System.getenv("MYSQL_USERNAME"), System.getenv("MYSQL_PASSWORD"));
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(config);
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new ClassPathResource("mapper/CompanyMapper.xml"),
                    new ClassPathResource("mapper/TagMapper.xml"), new ClassPathResource("mapper/GameMapper.xml"));
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        ICompanyService companies(CompanyMapper mapper, GameCompanyMapper relations) {
            return new CompanyServiceImpl(mapper, relations);
        }

        @Bean
        ITagService tags(TagMapper mapper, GameTagMapper relations) {
            return new TagServiceImpl(mapper, relations);
        }

        @Bean
        GameRelationsService relations(JdbcTemplate jdbc, ICompanyService companies, ITagService tags) {
            return new GameRelationsService(jdbc, companies, tags);
        }

        @Bean
        GamePublicationCache publicationCache() { return mock(GamePublicationCache.class); }

        @Bean
        GameCatalogService catalog(GameMapper mapper, GameRelationsService relations, GamePublicationCache cache) {
            return new GameCatalogService(mapper, relations, cache);
        }

        @Bean
        ExternalLinkStore externalLinks(JdbcTemplate jdbc, GameMapper mapper) { return new ExternalLinkStore(jdbc, mapper); }
    }
}
