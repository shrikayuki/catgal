package com.catgal.server.service.listener;

import Message.FavoriteSyncMessage;
import com.catgal.common.autoconfigure.mq.ConfirmedErrorMessageRecoverer;
import com.catgal.server.config.mq.LikeCountMqConfig;
import com.catgal.server.service.IFavoriteItemService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MethodRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.core.ResolvableType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;

import static com.catgal.common.constants.Constant.REQUEST_ID_HEADER;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 独立临时队列复用生产重试工厂，真实 JSON 通过方法端点进入实际监听器。 */
@EnabledIfEnvironmentVariable(named = "CATGAL_FAVORITE_MQ_TEST", matches = "1")
class FavoriteMqLiveTest {
    private final IFavoriteItemService service = mock(IFavoriteItemService.class);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final CountDownLatch handled = new CountDownLatch(1);
    private final CountDownLatch recovered = new CountDownLatch(1);
    private CachingConnectionFactory connection;
    private RabbitAdmin admin;
    private RabbitTemplate publisher;
    private SimpleMessageListenerContainer container;
    private String queueName;
    private String workExchange;
    private String errorQueue;
    private String errorExchange;
    private Binding workBinding;

    @BeforeEach void setup() throws Exception {
        connection = new CachingConnectionFactory(System.getenv("RABBITMQ_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
        connection.setUsername(System.getenv("RABBITMQ_USERNAME"));
        connection.setPassword(System.getenv("RABBITMQ_PASSWORD"));
        connection.setVirtualHost(System.getenv().getOrDefault("RABBITMQ_VHOST", "/"));
        connection.setConnectionTimeout(5000);
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        admin = new RabbitAdmin(connection);
        queueName = "catgal.test.favorite." + UUID.randomUUID();
        workExchange = queueName + ".work-exchange";
        errorQueue = queueName + ".error";
        errorExchange = queueName + ".error-exchange";
        Queue work = new Queue(queueName, false, true, false);
        Queue errors = new Queue(errorQueue, false, true, false);
        DirectExchange workRoutes = new DirectExchange(workExchange, false, false);
        DirectExchange exchange = new DirectExchange(errorExchange, false, true);
        admin.declareQueue(work);
        admin.declareQueue(errors);
        admin.declareExchange(workRoutes);
        admin.declareExchange(exchange);
        workBinding = BindingBuilder.bind(work).to(workRoutes).with(queueName);
        admin.declareBinding(workBinding);
        admin.declareBinding(BindingBuilder.bind(errors).to(exchange).with("failed"));

        var converter = new Jackson2JsonMessageConverter();
        publisher = new RabbitTemplate(connection);
        publisher.setMessageConverter(converter);
        publisher.setMandatory(true);
        var recoverer = new ConfirmedErrorMessageRecoverer(connection, errorExchange, "failed");
        ObjectProvider<ContainerCustomizer<SimpleMessageListenerContainer>> customizers =
                new DefaultListableBeanFactory().getBeanProvider(ResolvableType.forClassWithGenerics(
                        ContainerCustomizer.class, SimpleMessageListenerContainer.class));
        var factory = new LikeCountMqConfig().favoriteListenerContainerFactory(
                new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()), connection,
                (message, cause) -> { recoverer.recover(message, cause); recovered.countDown(); }, customizers);
        factory.setMessageConverter(converter);
        factory.setPrefetchCount(1);
        var handlerFactory = new DefaultMessageHandlerMethodFactory();
        handlerFactory.afterPropertiesSet();
        var endpoint = new MethodRabbitListenerEndpoint();
        endpoint.setId(queueName);
        endpoint.setQueueNames(queueName);
        endpoint.setBean(new FavoriteSyncConsumer(service));
        endpoint.setMethod(FavoriteSyncConsumer.class.getMethod("handleFavoriteSync", FavoriteSyncMessage.class));
        endpoint.setMessageHandlerMethodFactory(handlerFactory);
        doAnswer(call -> {
            calls.incrementAndGet();
            if (failures.getAndDecrement() > 0) throw new DataAccessResourceFailureException("isolated transient database failure");
            handled.countDown();
            return null;
        }).when(service).syncFavorite(anyLong(), anyLong(), anyLong());
        container = factory.createListenerContainer(endpoint);
        container.start();
    }

    @AfterEach void cleanup() {
        try { if (container != null) container.stop(); }
        finally {
            try {
                if (admin != null) {
                    if (queueName != null) admin.deleteQueue(queueName);
                    if (errorQueue != null) admin.deleteQueue(errorQueue);
                    if (workExchange != null) admin.deleteExchange(workExchange);
                    if (errorExchange != null) admin.deleteExchange(errorExchange);
                }
            } finally { if (connection != null) connection.destroy(); }
        }
    }

    private void publish(int operation) {
        publisher.convertAndSend(workExchange, queueName, FavoriteSyncMessage.builder().userId(6L)
                .folderId(10L).gameId(20L).operation(operation).build(), message -> {
            message.getMessageProperties().setHeader(REQUEST_ID_HEADER, "isolated-favorite-trace");
            return message;
        });
    }

    private int queued(String name) {
        return RabbitErrorReplayTestSupport.queued(admin, name);
    }

    @Test void validLegacyJsonPayloadReachesTheExistingMethodSignature() throws Exception {
        publish(1);
        assertTrue(handled.await(10, TimeUnit.SECONDS));
        container.stop();
        verify(service).syncFavorite(10L, 6L, 20L);
        assertEquals(1, calls.get());
        assertEquals(0, queued(queueName));
        assertEquals(0, queued(errorQueue));
    }

    @Test void temporaryFailuresRetryAndSucceedWithinTheConfiguredLimit() throws Exception {
        failures.set(2);
        publish(0);
        assertTrue(handled.await(15, TimeUnit.SECONDS));
        container.stop();
        assertEquals(3, calls.get());
        assertEquals(0, queued(queueName));
        assertEquals(0, queued(errorQueue));
    }

    @Test void exhaustedFailuresAreTransferredToTheRecoveryQueue() throws Exception {
        failures.set(100);
        publish(0);
        assertTrue(recovered.await(15, TimeUnit.SECONDS));
        container.stop();
        assertEquals(3, calls.get());
        assertEquals(0, queued(queueName));
        assertEquals(1, queued(errorQueue));
    }

    @Test void invalidFieldsRecoverWithoutEnteringBusinessRetry() throws Exception {
        publish(-1);
        assertTrue(recovered.await(10, TimeUnit.SECONDS));
        container.stop();
        assertEquals(0, calls.get());
        verifyNoInteractions(service);
        assertEquals(0, queued(queueName));
        assertEquals(1, queued(errorQueue));
    }

    @Test void manualReplayRetainsUnroutableOriginalAndReconcilesTheSameFavoriteTarget() throws Exception {
        failures.set(3);
        publish(FavoriteSyncMessage.UNFAVORITE);
        assertTrue(recovered.await(15, TimeUnit.SECONDS));
        assertEquals(3, calls.get());
        assertEquals(1, queued(errorQueue));

        admin.removeBinding(workBinding);
        assertFalse(RabbitErrorReplayTestSupport.replayOne(publisher, errorQueue, workExchange, queueName,
                false, this::assertOriginalFavoriteMessage));
        RabbitErrorReplayTestSupport.awaitRequeued(admin, errorQueue);
        assertEquals(3, calls.get());

        admin.declareBinding(workBinding);
        assertTrue(RabbitErrorReplayTestSupport.replayOne(publisher, errorQueue, workExchange, queueName,
                true, this::assertOriginalFavoriteMessage));
        assertTrue(handled.await(10, TimeUnit.SECONDS));
        container.stop();
        // 即使原件是取消通知，恢复后仍只调用当前关系校准，不做一次无条件删除。
        verify(service, times(4)).syncFavorite(10L, 6L, 20L);
        assertEquals(0, queued(queueName));
        assertEquals(0, queued(errorQueue));
    }

    private void assertOriginalFavoriteMessage(Message original) {
        assertEquals("isolated-favorite-trace", original.getMessageProperties().getHeader(REQUEST_ID_HEADER));
        var payload = assertDoesNotThrow(() -> new ObjectMapper().readValue(original.getBody(), FavoriteSyncMessage.class));
        assertEquals(6L, payload.getUserId());
        assertEquals(10L, payload.getFolderId());
        assertEquals(20L, payload.getGameId());
        assertEquals(FavoriteSyncMessage.UNFAVORITE, payload.getOperation());
    }
}
