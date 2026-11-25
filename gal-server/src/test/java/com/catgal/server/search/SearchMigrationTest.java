package com.catgal.server.search;

import java.nio.charset.StandardCharsets;
import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.resource.StringResource;
import org.flywaydb.database.mysql.MySQLParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchMigrationTest {
    @Test
    void flywayParsesAllTriggersAndBackfillWithoutDatabaseAccess() throws Exception {
        String sql = new ClassPathResource("db/migration/V5__create_game_search_outbox.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        MySQLParser parser = new MySQLParser(new ClassicConfiguration(), new ParsingContext());
        int count = 0;
        int triggers = 0;
        try (var statements = parser.parse(new StringResource(sql))) {
            while (statements.hasNext()) {
                String statement = statements.next().getSql();
                count++;
                if (statement.contains("CREATE TRIGGER")) {
                    triggers++;
                }
            }
        }
        assertEquals(9, triggers);
        assertEquals(20, count);
    }

    @Test
    void parsesV6ResourceAndNameChangeTriggers() throws Exception {
        String sql = new ClassPathResource("db/migration/V6__expand_catalog_search.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        MySQLParser parser = new MySQLParser(new ClassicConfiguration(), new ParsingContext());
        int count = 0;
        int triggers = 0;
        try (var statements = parser.parse(new StringResource(sql))) {
            while (statements.hasNext()) {
                if (statements.next().getSql().contains("CREATE TRIGGER")) triggers++;
                count++;
            }
        }
        assertEquals(10, triggers);
        assertEquals(23, count);
    }
}
