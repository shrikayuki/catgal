package com.catgal.server.service.listener;

import Message.RewardCommittedMessage;
import com.catgal.common.autoconfigure.mq.ConfirmedErrorMessageRecoverer;
import com.catgal.server.config.mq.LikeCountMqConfig;
import com.catgal.server.service.IRewardOutboxService;
import com.catgal.server.service.impl.ConfirmedRewardPublisherImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.MethodRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.core.ResolvableType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只声明和清理随机测试交换机/队列，真实 broker 验证 confirm 与 mandatory return。 */
@EnabledIfEnvironmentVariable(named = "CATGAL_REWARD_MQ_TEST", matches = "1")
class RewardMqLiveTest {
    private CachingConnectionFactory connection;
    private RabbitAdmin admin;
    private RabbitTemplate receiver;
    private ConfirmedRewardPublisherImpl publisher;
    private String name;
    private String errorQueue;
    private String errorExchange;
    private Binding workBinding;
    private SimpleMessageListenerContainer container;

    @BeforeEach void setup() {
        connection = new CachingConnectionFactory(System.getenv("RABBITMQ_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
        connection.setUsername(System.getenv("RABBITMQ_USERNAME")); connection.setPassword(System.getenv("RABBITMQ_PASSWORD"));
        connection.setVirtualHost(System.getenv().getOrDefault("RABBITMQ_VHOST", "/"));
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true); connection.setConnectionTimeout(5000);
        admin = new RabbitAdmin(connection); name = "catgal.test.reward." + UUID.randomUUID();
        DirectExchange exchange = new DirectExchange(name, false, false);
        Queue queue = new Queue(name, false, false, false);
        admin.declareExchange(exchange); admin.declareQueue(queue);
        workBinding = BindingBuilder.bind(queue).to(exchange).with("test");
        admin.declareBinding(workBinding);
        publisher = new ConfirmedRewardPublisherImpl(connection, new Jackson2JsonMessageConverter());
        receiver = new RabbitTemplate(connection);
        receiver.setMandatory(true);
    }
    @AfterEach void cleanup() {
        try { if (container != null) container.stop(); }
        finally {
            try {
                if (admin != null && name != null) {
                    admin.deleteQueue(name);
                    admin.deleteExchange(name);
                    if (errorQueue != null) admin.deleteQueue(errorQueue);
                    if (errorExchange != null) admin.deleteExchange(errorExchange);
                }
            } finally { if (connection != null) connection.destroy(); }
        }
    }
    @Test void confirmedEventTraversesRealJsonAndCallsProductionConsumer() throws Exception {
        publisher.send(name, "test", new RewardCommittedMessage(1, 77L));
        Message message = receiver.receive(name, 5000);
        assertNotNull(message);
        RewardCommittedMessage event = new ObjectMapper().readValue(message.getBody(), RewardCommittedMessage.class);
        IRewardOutboxService outbox = mock(IRewardOutboxService.class);
        new RewardCommittedListener(outbox).consume(event);
        verify(outbox).project(77L); assertEquals(0, publisher.inFlight());
    }
    @Test void unroutableMessageIsFailureEvenWhenExchangeExistsAndBrokerAcks() {
        assertThrows(IllegalStateException.class, () -> publisher.send(name, "missing-routing-key", new RewardCommittedMessage(1, 77L)));
        assertNull(receiver.receive(name)); assertEquals(0, publisher.inFlight());
    }
    @Test void unsupportedProtocolCannotInvokeProjection() {
        IRewardOutboxService outbox = mock(IRewardOutboxService.class);
        assertThrows(org.springframework.amqp.AmqpRejectAndDontRequeueException.class,
                () -> new RewardCommittedListener(outbox).consume(new RewardCommittedMessage(2, 77L)));
        verifyNoInteractions(outbox);
    }
    @Test void brokerChannelFailureClearsTheRegisteredPendingConfirmation() {
        // 仅使用本例随机命名空间的不存在交换机，broker 关闭本发送 channel，其他队列继续工作。
        assertThrows(IllegalStateException.class,
                () -> publisher.send(name + ".absent", "test", new RewardCommittedMessage(1, 77L)));
        assertEquals(0, publisher.inFlight());
        assertNull(receiver.receive(name));
    }

    @Test void manualReplayRetainsUnroutableOriginalAndProjectsTheSameCommittedEvent() throws Exception {
        var recovered = new CountDownLatch(1);
        var projected = new CountDownLatch(1);
        IRewardOutboxService outbox = mock(IRewardOutboxService.class);
        doThrow(new DataAccessResourceFailureException("isolated projection failure"))
                .doThrow(new DataAccessResourceFailureException("isolated projection failure"))
                .doThrow(new DataAccessResourceFailureException("isolated projection failure"))
                .doAnswer(invocation -> { projected.countDown(); return null; })
                .when(outbox).project(77L);
        startProjectionListener(outbox, recovered);

        publisher.send(name, "test", new RewardCommittedMessage(1, 77L));
        assertTrue(recovered.await(15, TimeUnit.SECONDS));
        assertEquals(1, RabbitErrorReplayTestSupport.queued(admin, errorQueue));

        admin.removeBinding(workBinding);
        assertFalse(RabbitErrorReplayTestSupport.replayOne(receiver, errorQueue, name, "test", false,
                this::assertCommittedEvent));
        RabbitErrorReplayTestSupport.awaitRequeued(admin, errorQueue);
        verify(outbox, times(3)).project(77L);

        admin.declareBinding(workBinding);
        assertTrue(RabbitErrorReplayTestSupport.replayOne(receiver, errorQueue, name, "test", true,
                this::assertCommittedEvent));
        assertTrue(projected.await(10, TimeUnit.SECONDS));
        container.stop();
        // ACTIVE 重放同一个已提交事件的投影，账务操作已在发送前提交。
        verify(outbox, times(4)).project(77L);
        verifyNoMoreInteractions(outbox);
        assertEquals(0, RabbitErrorReplayTestSupport.queued(admin, name));
        assertEquals(0, RabbitErrorReplayTestSupport.queued(admin, errorQueue));
    }

    private void startProjectionListener(IRewardOutboxService outbox, CountDownLatch recovered) throws Exception {
        errorQueue = name + ".error";
        errorExchange = name + ".error-exchange";
        Queue errors = new Queue(errorQueue, false, false, false);
        DirectExchange exchange = new DirectExchange(errorExchange, false, false);
        admin.declareQueue(errors);
        admin.declareExchange(exchange);
        admin.declareBinding(BindingBuilder.bind(errors).to(exchange).with("failed"));

        var recoverer = new ConfirmedErrorMessageRecoverer(connection, errorExchange, "failed");
        var customizers = new DefaultListableBeanFactory().<ContainerCustomizer<SimpleMessageListenerContainer>>getBeanProvider(
                ResolvableType.forClassWithGenerics(ContainerCustomizer.class, SimpleMessageListenerContainer.class));
        var factory = new LikeCountMqConfig().rewardListenerContainerFactory(
                new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()), connection,
                (message, failure) -> { recoverer.recover(message, failure); recovered.countDown(); }, customizers);
        factory.setMessageConverter(new Jackson2JsonMessageConverter());
        factory.setPrefetchCount(1);
        var handlerFactory = new DefaultMessageHandlerMethodFactory();
        handlerFactory.afterPropertiesSet();
        var endpoint = new MethodRabbitListenerEndpoint();
        endpoint.setId(name);
        endpoint.setQueueNames(name);
        endpoint.setBean(new RewardCommittedListener(outbox));
        endpoint.setMethod(RewardCommittedListener.class.getMethod("consume", RewardCommittedMessage.class));
        endpoint.setMessageHandlerMethodFactory(handlerFactory);
        container = factory.createListenerContainer(endpoint);
        container.start();
    }

    private void assertCommittedEvent(Message original) {
        assertNotNull(original.getMessageProperties().getCorrelationId());
        var payload = assertDoesNotThrow(() -> new ObjectMapper().readValue(original.getBody(), RewardCommittedMessage.class));
        assertEquals(new RewardCommittedMessage(1, 77L), payload);
    }
}
