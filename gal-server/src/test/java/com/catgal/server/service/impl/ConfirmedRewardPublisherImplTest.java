package com.catgal.server.service.impl;

import java.net.ConnectException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 发送尚未进入 channel 时失败，不应留下永远等不到回调的待确认计数。 */
class ConfirmedRewardPublisherImplTest {
    @Test
    void connectionFailureBeforePublishingDoesNotLeakOutstandingCountAcrossRetries() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenThrow(new AmqpConnectException(new ConnectException("isolated refusal")));
        var publisher = new ConfirmedRewardPublisherImpl(factory, new Jackson2JsonMessageConverter());

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThrows(AmqpConnectException.class, () -> publisher.send("test", "test", Map.of("id", 1)));
            assertEquals(0, publisher.inFlight());
        }
        verify(factory, times(3)).createConnection();
    }

    @Test
    void conversionFailureBeforeConnectingDoesNotLeakOutstandingCount() {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        MessageConverter converter = mock(MessageConverter.class);
        when(converter.toMessage(any(), any())).thenThrow(new MessageConversionException("isolated conversion failure"));
        var publisher = new ConfirmedRewardPublisherImpl(factory, converter);

        assertThrows(MessageConversionException.class, () -> publisher.send("test", "test", Map.of("id", 1)));
        assertEquals(0, publisher.inFlight());
        verify(factory, never()).createConnection();
    }
}
