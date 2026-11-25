package com.catgal.server.search;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameSearchMaintenanceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SearchOutbox outbox = mock(SearchOutbox.class);
    private final GameIndexSource source = mock(GameIndexSource.class);
    private final GameIndexWriter writer = mock(GameIndexWriter.class);
    private final SearchProperties properties = new SearchProperties();
    private final GameSearchMaintenance maintenance = new GameSearchMaintenance(jdbc, properties, outbox, source, writer);
    private final List<SearchOutbox.Event> events = List.of(new SearchOutbox.Event(1, 4));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void mockDistributedLock() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn("catgal:game-search");
        when(result.getInt(1)).thenReturn(1);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation ->
                ((ConnectionCallback<Object>) invocation.getArgument(0)).doInConnection(connection));
        properties.setEnabled(true);
    }

    @Test
    void failedBulkRemainsPendingForRetry() {
        when(outbox.pending(anyInt())).thenReturn(events);
        when(source.load(anyList())).thenReturn(Map.of());
        doThrow(new IllegalStateException("429")).when(writer).write(anyString(), anyList(), anyMap());
        maintenance.synchronize();
        verify(outbox).failed(events, "IllegalStateException");
        verify(outbox, never()).acknowledge(anyList());
    }

    @Test
    void successfulBulkAndRefreshPrecedeAcknowledgement() {
        when(outbox.pending(anyInt())).thenReturn(events, List.of());
        when(source.load(anyList())).thenReturn(Map.of());
        maintenance.synchronize();
        var order = inOrder(writer, outbox);
        order.verify(writer).write(eq("catgal-game"), eq(List.of(1L)), anyMap());
        order.verify(writer).refresh("catgal-game");
        order.verify(outbox).acknowledge(events);
    }

    @Test
    void failedRebuildPreservesLiveAliasAndPendingEvents() {
        when(writer.createIndex()).thenReturn("catgal-game-v1-test");
        when(source.nextIds(anyLong(), anyInt())).thenReturn(List.of(1L));
        when(source.load(anyList())).thenReturn(Map.of());
        doThrow(new IllegalStateException("bulk failure")).when(writer).write(anyString(), anyList(), anyMap());
        assertThrows(IllegalStateException.class, maintenance::rebuild);
        verify(writer, never()).activate(anyString());
        verify(outbox, never()).acknowledge(anyList());
    }

    @Test
    void rebuildReplaysPendingChangesBeforeAliasSwitchAndKeepsThemForLiveWorker() {
        when(writer.createIndex()).thenReturn("catgal-game-v1-test");
        when(source.nextIds(anyLong(), anyInt())).thenReturn(List.of(1L), List.of());
        when(source.load(anyList())).thenReturn(Map.of());
        when(outbox.after(anyLong(), anyInt())).thenReturn(events, List.of());
        assertEquals(1L, maintenance.rebuild().get("scannedGames"));
        var order = inOrder(writer);
        order.verify(writer).createIndex();
        order.verify(writer, times(2)).write(eq("catgal-game-v1-test"), eq(List.of(1L)), anyMap());
        order.verify(writer).refresh("catgal-game-v1-test");
        order.verify(writer).activate("catgal-game-v1-test");
        verify(outbox, never()).acknowledge(anyList());
    }
}
