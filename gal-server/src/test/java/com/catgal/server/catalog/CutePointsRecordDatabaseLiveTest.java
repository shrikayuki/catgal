package com.catgal.server.catalog;

import com.catgal.common.context.UserContext;
import com.catgal.common.context.UserInfo;
import com.catgal.common.domain.query.ScrollPageQuery;
import com.catgal.server.mapper.CutePointsRecordMapper;
import com.catgal.server.security.CurrentUserProvider;
import com.catgal.server.service.ICutePointsRecordService;
import com.catgal.server.service.impl.CutePointsRecordServiceImpl;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig({CatalogDatabaseLiveTest.Config.class, CutePointsRecordDatabaseLiveTest.Config.class})
@EnabledIfEnvironmentVariable(named = "CATGAL_POINTS_RECORD_DB_TEST", matches = "1")
@Transactional
class CutePointsRecordDatabaseLiveTest {
    @Autowired private ICutePointsRecordService records;
    @Autowired private JdbcTemplate jdbc;
    private String prefix;
    private Long owner;

    @BeforeEach
    void setup() {
        prefix = "cp" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        owner = createUser(prefix);
        UserContext.setUser(UserInfo.builder().userId(owner).build());
    }

    @AfterEach
    void clear() { UserContext.clear(); }

    private Long createUser(String username) {
        jdbc.update("INSERT INTO user(username,password,role) VALUES (?, ?, 0)", username, "test-only-not-login");
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    private Long insert(Long userId, String suffix, LocalDateTime time) {
        String businessId = prefix + "-" + suffix;
        jdbc.update("INSERT INTO cute_points_record(user_id,cute_points,type,biz_id,before_points,after_points,remark,create_time) "
                        + "VALUES (?,10,99,?,0,10,'isolated cursor test',?)",
                userId, businessId, time == null ? null : Timestamp.valueOf(time));
        return jdbc.queryForObject("SELECT id FROM cute_points_record WHERE biz_id = ?", Long.class, businessId);
    }

    @Test
    void pagesTiedAndNullTimesWithoutDuplicatesOrOtherUsersRecords() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 15, 12, 0);
        List<Long> tied = new ArrayList<>();
        for (int index = 0; index < 3; index++) tied.add(insert(owner, "tie-" + index, time));
        Long older = insert(owner, "older", time.minusSeconds(1));
        List<Long> undated = new ArrayList<>();
        for (int index = 0; index < 3; index++) undated.add(insert(owner, "null-" + index, null));
        insert(createUser(prefix + "o"), "other-record", time.plusDays(1));

        Collections.reverse(tied);
        Collections.reverse(undated);
        List<Long> expected = new ArrayList<>(tied);
        expected.add(older);
        expected.addAll(undated);
        List<Long> actual = new ArrayList<>();
        ScrollPageQuery query = new ScrollPageQuery();
        query.setPageSize(2);
        boolean sawNullCursor = false;
        for (int page = 0; page < 10; page++) {
            var result = records.queryMyRecords(query);
            actual.addAll(result.list().stream().map(row -> row.id()).toList());
            assertTrue(result.list().stream().allMatch(row -> row.type() == 99));
            if (!result.hasMore()) {
                assertNull(result.nextCursor());
                break;
            }
            assertNotNull(result.nextCursor());
            sawNullCursor |= new String(Base64.getUrlDecoder().decode(result.nextCursor()),
                    java.nio.charset.StandardCharsets.UTF_8).startsWith("null|");
            query.setCursor(result.nextCursor());
        }
        assertEquals(expected, actual);
        assertTrue(sawNullCursor);
    }

    @Test
    void emptyAccountReturnsNoCursor() {
        var page = records.queryMyRecords(new ScrollPageQuery());
        assertTrue(page.list().isEmpty());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
    }

    @Test
    void cursorContinuesAfterItsSourceRowIsRemoved() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 15, 12, 0);
        Long first = insert(owner, "first", time);
        Long second = insert(owner, "second", time);
        ScrollPageQuery query = new ScrollPageQuery();
        query.setPageSize(1);
        var initial = records.queryMyRecords(query);
        assertEquals(second, initial.list().get(0).id());
        jdbc.update("DELETE FROM cute_points_record WHERE id = ? AND user_id = ? AND biz_id = ?",
                second, owner, prefix + "-second");
        query.setCursor(initial.nextCursor());
        var remaining = records.queryMyRecords(query);
        assertEquals(List.of(first), remaining.list().stream().map(row -> row.id()).toList());
        assertFalse(remaining.hasMore());
    }

    @Configuration
    static class Config {
        @Bean
        ICutePointsRecordService pointsRecordService(CutePointsRecordMapper mapper) {
            return new CutePointsRecordServiceImpl(mapper, new CurrentUserProvider());
        }
    }
}
