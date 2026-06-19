package com.patchlens.config;

import com.patchlens.dto.ReviewJobMessage;
import com.patchlens.service.ReviewJobService;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class RabbitMQConfig {

    // --- Exchange names ---
    public static final String REVIEWS_EXCHANGE         = "patchlens.reviews";
    public static final String REVIEWS_DLX              = "patchlens.reviews.dlx";

    // --- Queue names ---
    public static final String REVIEW_JOBS_QUEUE        = "review.jobs";
    public static final String REVIEW_JOBS_DLQ          = "review.jobs.dlq";

    // --- Routing keys ---
    public static final String REVIEW_JOBS_ROUTING_KEY  = "review.jobs";

    // --- Main exchange (direct, durable) ---
    @Bean
    public DirectExchange reviewsExchange() {
        return ExchangeBuilder.directExchange(REVIEWS_EXCHANGE).durable(true).build();
    }

    // --- Dead-letter exchange ---
    @Bean
    public DirectExchange reviewsDlx() {
        return ExchangeBuilder.directExchange(REVIEWS_DLX).durable(true).build();
    }

    // --- Main queue (routes failed messages to DLX) ---
    @Bean
    public Queue reviewJobsQueue() {
        return QueueBuilder.durable(REVIEW_JOBS_QUEUE)
                .withArgument("x-dead-letter-exchange", REVIEWS_DLX)
                .withArgument("x-dead-letter-routing-key", REVIEW_JOBS_ROUTING_KEY)
                .build();
    }

    // --- Dead-letter queue ---
    @Bean
    public Queue reviewJobsDlq() {
        return QueueBuilder.durable(REVIEW_JOBS_DLQ).build();
    }

    // --- Bindings ---
    @Bean
    public Binding reviewJobsBinding(Queue reviewJobsQueue, DirectExchange reviewsExchange) {
        return BindingBuilder.bind(reviewJobsQueue).to(reviewsExchange).with(REVIEW_JOBS_ROUTING_KEY);
    }

    @Bean
    public Binding reviewJobsDlqBinding(Queue reviewJobsDlq, DirectExchange reviewsDlx) {
        return BindingBuilder.bind(reviewJobsDlq).to(reviewsDlx).with(REVIEW_JOBS_ROUTING_KEY);
    }

    // --- Message converter (Jackson 3 / Spring AMQP 4.x) ---
    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    // --- RabbitTemplate (uses JSON converter) ---
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    // --- Listener container factory ---
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            MethodInterceptor retryInterceptor) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        // CRITICAL: do NOT requeue on rejection — messages must go to DLX, not loop forever
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryInterceptor);
        return factory;
    }

    // --- Retry interceptor: 3 attempts with exponential back-off (2s/4s/8s) ---
    @Bean
    public MethodInterceptor retryInterceptor(MessageRecoverer messageRecoverer) {
        return RetryInterceptorBuilder.stateless()
                .maxRetries(3)
                .backOffOptions(2000, 2.0, 8000)
                .recoverer(messageRecoverer)
                .build();
    }

    // --- Message recoverer: mark job as DEAD_LETTER then reject without requeue ---
    @Bean
    public MessageRecoverer messageRecoverer(ReviewJobService reviewJobService,
                                             ObjectMapper objectMapper) {
        return (message, cause) -> {
            try {
                ReviewJobMessage msg = objectMapper.readValue(message.getBody(), ReviewJobMessage.class);
                reviewJobService.markDeadLetter(msg.jobId(), cause.getMessage());
            } catch (Exception e) {
                // Best-effort — the message must still be rejected regardless
            }
            // Throw so Spring AMQP nacks the message → routes to DLQ
            throw new AmqpRejectAndDontRequeueException("Max retries exhausted", cause);
        };
    }
}
