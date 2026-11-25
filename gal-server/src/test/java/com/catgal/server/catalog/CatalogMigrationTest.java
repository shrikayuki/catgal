package com.catgal.server.catalog;

import java.nio.charset.StandardCharsets;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.resource.StringResource;
import org.flywaydb.database.mysql.MySQLParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class CatalogMigrationTest {
    @Test
    void parsesFiveCatalogAlterStatementsAndFourRestrictiveForeignKeys() throws Exception {
        String sql = new ClassPathResource("db/migration/V7__harden_catalog_maintenance.sql").getContentAsString(StandardCharsets.UTF_8);
        int statements = 0;
        try (var parsed = new MySQLParser(new ClassicConfiguration(), new ParsingContext()).parse(new StringResource(sql))) {
            while (parsed.hasNext()) {
                assertTrue(parsed.next().getSql().contains("ALTER TABLE"));
                statements++;
            }
        }
        assertEquals(5, statements);
        assertEquals(4, sql.split("FOREIGN KEY", -1).length - 1);
        assertFalse(sql.contains("CASCADE"));
    }
}
