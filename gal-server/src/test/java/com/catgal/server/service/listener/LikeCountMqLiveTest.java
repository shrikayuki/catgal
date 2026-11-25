package com.catgal.server.service.listener;

import Message.LikeCountMessage;
import com.catgal.common.autoconfigure.mq.ConfirmedErrorMessageRecoverer;
import com.catgal.server.config.mq.LikeCountMqConfig;
import com.catgal.server.service.ILikeRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.AmqpMessageReturnedException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.AmqpNackReceivedException;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.catgal.common.constants.Constant.REQUEST_ID_HEADER;
import static org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer.X_ORIGINAL_EXCHANGE;
import static org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer.X_ORIGINAL_ROUTING_KEY;

/** 独立临时队列复用生产重试工厂，真实 JSON 通过方法端点进入实际监听器。 */
@EnabledIfEnvironmentVariable(named = "CATGAL_LIKE_MQ_TEST", matches = "1")
class LikeCountMqLiveTest {
    private final ILikeRecordService service = mock(ILikeRecordService.class);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final CountDownLatch handled = new CountDownLatch(1);
    private final CountDownLatch recovered = new CountDownLatch(1);
    private final CountDownLatch forwardingFailed = new CountDownLatch(1);
    private final AtomicReference<Throwable> forwardingCause = new AtomicReference<>();
    private final AtomicBoolean recoveredRedelivery = new AtomicBoolean();
    private CachingConnectionFactory connection;
    private RabbitAdmin admin;
    private RabbitTemplate publisher;
    private SimpleMessageListenerContainer container;
    private String queueName;
    private String workExchange;
    private String errorQueue;
    private String errorExchange;
    private Binding workBinding;
    private Binding errorBinding;

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
        queueName = "catgal.test.like-count." + UUID.randomUUID();
        workExchange = queueName + ".work-exchange";
        errorQueue = queueName + ".error";
        errorExchange = queueName + ".error-exchange";
        Queue work = new Queue(queueName, false, true, false);
        Queue errors = new Queue(errorQueue, false, true, false);
        DirectExchange workRoutes = new DirectExchange(workExchange, false, false);
        // 删除最后一条绑定时继续保留交换机，以便真实触发不可路由退回。
        DirectExchange exchange = new DirectExchange(errorExchange, false, false);
        admin.declareQueue(work);
        admin.declareQueue(errors);
        admin.declareExchange(workRoutes);
        admin.declareExchange(exchange);
        workBinding = BindingBuilder.bind(work).to(workRoutes).with(queueName);
        errorBinding = BindingBuilder.bind(errors).to(exchange).with("failed");
        admin.declareBinding(workBinding);
        admin.declareBinding(errorBinding);

        var converter = new Jackson2JsonMessageConverter();
        publisher = new RabbitTemplate(connection);
        publisher.setMessageConverter(converter);
        publisher.setMandatory(true);
        var recoverer = new ConfirmedErrorMessageRecoverer(connection, errorExchange, "failed");
        ObjectProvider<ContainerCustomizer<SimpleMessageListenerContainer>> customizers =
                new DefaultListableBeanFactory().getBeanProvider(ResolvableType.forClassWithGenerics(
                        ContainerCustomizer.class, SimpleMessageListenerContainer.class));
        var factory = new LikeCountMqConfig().likeCountListenerContainerFactory(
                new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()), connection,
                (message, cause) -> {
                    try {
                        recoverer.recover(message, cause);
                        recoveredRedelivery.set(Boolean.TRUE.equals(message.getMessageProperties().isRedelivered()));
                        recovered.countDown();
                    } catch (ImmediateRequeueAmqpException failure) {
                        forwardingCause.set(failure.getCause());
                        forwardingFailed.countDown();
                        throw failure;
                    }
                }, customizers);
        factory.setDefaultRequeueRejected(false);
        factory.setMessageConverter(converter);
        factory.setPrefetchCount(1);
        var handlerFactory = new DefaultMessageHandlerMethodFactory();
        handlerFactory.afterPropertiesSet();
        var endpoint = new MethodRabbitListenerEndpoint();
        endpoint.setId(queueName);
        endpoint.setQueueNames(queueName);
        endpoint.setBean(new LikeCountListener(service));
        endpoint.setMethod(LikeCountListener.class.getMethod("listenCommentLikeTimesChange", List.class));
        endpoint.setMessageHandlerMethodFactory(handlerFactory);
        doAnswer(call -> {
            calls.incrementAndGet();
            if (failures.getAndDecrement() > 0) throw new DataAccessResourceFailureException("isolated transient database failure");
            handled.countDown();
            return null;
        }).when(service).syncLikeCounts(anyString(), anyList());
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

    private void publish(long count) {
        publisher.convertAndSend(workExchange, queueName, new ArrayList<>(List.of(
                LikeCountMessage.builder().bizType("comment").bizId(20L).likeCount(count).build())), message -> {
            message.getMessageProperties().setHeader(REQUEST_ID_HEADER, "isolated-trace");
            return message;
        });
    }

    private int queued(String name) {
        return RabbitErrorReplayTestSupport.queued(admin, name);
    }

    @Test void validLegacyJsonPayloadReachesTheExistingMethodSignature() throws Exception {
        publish(999);
        assertTrue(handled.await(10, TimeUnit.SECONDS));
        container.stop();
        verify(service).syncLikeCounts("comment", List.of(20L));
        assertEquals(1, calls.get());
        assertEquals(0, queued(queueName));
        assertEquals(0, queued(errorQueue));
    }

    @Test void temporaryFailuresRetryAndSucceedWithinTheConfiguredLimit() throws Exception {
        failures.set(2);
        publish(3);
        assertTrue(handled.await(15, TimeUnit.SECONDS));
        container.stop();
        assertEquals(3, calls.get());
        assertEquals(0, queued(queueName));
        assertEquals(0, queued(errorQueue));
    }

    @Test void exhaustedFailuresAreTransferredToTheRecoveryQueue() throws Exception {
        failures.set(100);
        publish(3);
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

    @Test void manualReplayKeepsUnroutableMessageAndConsumesItAfterBindingReturns() throws Exception {
        failures.set(3);
        publish(999);
        assertTrue(recovered.await(15, TimeUnit.SECONDS));
        assertEquals(3, calls.get());
        assertEquals(1, queued(errorQueue));

        admin.removeBinding(workBinding);
        assertFalse(RabbitErrorReplayTestSupport.replayOne(publisher, errorQueue, workExchange, queueName,
                false, this::assertOriginalLikeMessage));
        assertEquals(3, calls.get());
        RabbitErrorReplayTestSupport.awaitRequeued(admin, errorQueue);

        admin.declareBinding(workBinding);
        assertTrue(RabbitErrorReplayTestSupport.replayOne(publisher, errorQueue, workExchange, queueName,
                true, this::assertOriginalLikeMessage));
        assertTrue(handled.await(10, TimeUnit.SECONDS));
        container.stop();
        verify(service, times(4)).syncLikeCounts("comment", List.of(20L));
        assertEquals(0, queued(queueName));
        assertEquals(0, queued(errorQueue));
    }

    private void assertOriginalLikeMessage(Message original) {
        assertEquals("isolated-trace", original.getMessageProperties().getHeader(REQUEST_ID_HEADER));
        var payload = assertDoesNotThrow(() -> new ObjectMapper().readTree(original.getBody()));
        assertEquals(999L, payload.get(0).get("likeCount").longValue());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedErrorTransferRequeuesOriginalUntilRouteIsRestored(boolean missingExchange) throws Exception {
        if (missingExchange) admin.deleteExchange(errorExchange);
        else admin.removeBinding(errorBinding);
        publish(-1);

        assertTrue(forwardingFailed.await(12, TimeUnit.SECONDS));
        assertEquals(missingExchange ? AmqpNackReceivedException.class : AmqpMessageReturnedException.class,
                forwardingCause.get().getClass());
        assertEquals(0, queued(errorQueue));
        if (missingExchange) admin.declareExchange(new DirectExchange(errorExchange, false, false));
        admin.declareBinding(errorBinding);

        assertTrue(recovered.await(15, TimeUnit.SECONDS));
        container.stop();
        assertTrue(recoveredRedelivery.get(), "源队列必须重新投递同一消息");
        verifyNoInteractions(service);
        assertEquals(0, queued(queueName));
        assertEquals(1, queued(errorQueue));
        var failed = publisher.receive(errorQueue, 5000);
        assertNotNull(failed);
        assertEquals(-1L, new ObjectMapper().readTree(failed.getBody()).get(0).get("likeCount").longValue());
        var headers = failed.getMessageProperties().getHeaders();
        assertEquals("isolated-trace", headers.get(REQUEST_ID_HEADER));
        assertEquals(queueName, headers.get(X_ORIGINAL_ROUTING_KEY));
        assertEquals(workExchange, headers.get(X_ORIGINAL_EXCHANGE));
    }
}
