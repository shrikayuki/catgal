package com.catgal.server.service.listener;

import Message.FavoriteSyncMessage;
import com.catgal.server.service.IFavoriteItemService;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FavoriteSyncConsumerTest {
    private final IFavoriteItemService service = mock(IFavoriteItemService.class);
    private final FavoriteSyncConsumer consumer = new FavoriteSyncConsumer(service);

    private FavoriteSyncMessage message(Integer operation) {
        return FavoriteSyncMessage.builder().userId(6L).folderId(10L).gameId(20L).operation(operation).build();
    }

    @Test void nullAndUnknownOperationsCannotReachTheBusinessService() {
        for (Integer value : new Integer[]{null, -1, 2, Integer.MAX_VALUE}) {
            assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleFavoriteSync(message(value)));
        }
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleFavoriteSync(null));
        verifyNoInteractions(service);
    }

    @Test void missingAndNonPositiveIdsAreRejectedBeforeDatabaseAccess() {
        List<Consumer<FavoriteSyncMessage>> fields = List.of(
                v -> v.setUserId(null), v -> v.setUserId(0L), v -> v.setUserId(-1L),
                v -> v.setFolderId(null), v -> v.setFolderId(0L), v -> v.setFolderId(-1L),
                v -> v.setGameId(null), v -> v.setGameId(0L), v -> v.setGameId(-1L));
        for (var field : fields) {
            var value = message(1); field.accept(value);
            assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleFavoriteSync(value));
        }
        verifyNoInteractions(service);
    }

    @Test void bothLegacyOperationsCalibrateTheSameCurrentState() {
        consumer.handleFavoriteSync(message(1)); consumer.handleFavoriteSync(message(0));
        verify(service, times(2)).syncFavorite(10L, 6L, 20L);
    }

    @Test void ownershipFailuresRemainNonRetryable() {
        doThrow(new AmqpRejectAndDontRequeueException("isolated wrong owner"))
                .when(service).syncFavorite(anyLong(), anyLong(), anyLong());
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleFavoriteSync(message(1)));
    }

    @Test void transientFailuresPropagateToTheBoundedContainerRetry() {
        doThrow(new DataAccessResourceFailureException("isolated database failure"))
                .when(service).syncFavorite(anyLong(), anyLong(), anyLong());
        assertThrows(DataAccessResourceFailureException.class, () -> consumer.handleFavoriteSync(message(1)));
    }
}
