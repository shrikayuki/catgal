package com.catgal.server.search;

import java.sql.PreparedStatement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchOutboxTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void acknowledgeUsesObservedVersionSoConcurrentUpdatesRemainPending() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SearchOutbox outbox = new SearchOutbox(jdbc);
        List<SearchOutbox.Event> events = List.of(new SearchOutbox.Event(9L, 12L));
        outbox.acknowledge(events);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ParameterizedPreparedStatementSetter<SearchOutbox.Event>> setter = ArgumentCaptor.forClass(
                (Class) ParameterizedPreparedStatementSetter.class);
        verify(jdbc).batchUpdate(sql.capture(), eq(events), eq(1), setter.capture());
        assertTrue(sql.getValue().contains("WHERE game_id = ? AND version = ?"));
        assertTrue(sql.getValue().contains("pending = 0"));
        PreparedStatement statement = mock(PreparedStatement.class);
        setter.getValue().setValues(statement, events.get(0));
        verify(statement).setLong(1, 9L);
        verify(statement).setLong(2, 12L);
    }
}
