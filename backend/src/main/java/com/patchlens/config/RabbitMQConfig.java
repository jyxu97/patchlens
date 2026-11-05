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
import org.springframework.beans.factory.annotation.Value;
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
    /**
     * Bounded consumer concurrency (each consumer may run model calls and Docker builds) and
     * prefetch 1, so an idle worker never holds messages another worker could process.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            MethodInterceptor retryInterceptor,
            @Value("${patchlens.worker.concurrency:2}") int concurrency,
            @Value("${patchlens.worker.max-concurrency:4}") int maxConcurrency) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(Math.max(concurrency, maxConcurrency));
        factory.setPrefetchCount(1);
        // CRITICAL: do NOT requeue on rejection — messages must go to DLX, not loop forever
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryInterceptor);
        return factory;
    }

    /** Total deliveries attempted in-process for one message (1 initial + retries). */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    // --- Retry interceptor: exponential back-off, permanent errors are not retried ---
    @Bean
    public MethodInterceptor retryInterceptor(MessageRecoverer messageRecoverer,
                                              @Value("${patchlens.worker.max-attempts:" + DEFAULT_MAX_ATTEMPTS + "}") int maxAttempts) {
        return RetryInterceptorBuilder.stateless()
                .configureRetryPolicy(policy -> policy
                        .maxRetries(Math.max(0, maxAttempts - 1))   // Spring Framework 7: retries AFTER the first attempt
                        .delay(java.time.Duration.ofSeconds(2))
                        .multiplier(2.0)
                        .maxDelay(java.time.Duration.ofSeconds(8))
                        .predicate(RabbitMQConfig::isRetryable))
                .recoverer(messageRecoverer)
                .build();
    }

    /**
     * Permanent failures (explicit reject, non-retryable GitHub errors, missing job, bad
     * arguments) go straight to the recoverer/DLQ instead of burning retries. Listener exceptions
     * arrive wrapped, so the whole cause chain is inspected.
     */
    static boolean isRetryable(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof AmqpRejectAndDontRequeueException) return false;
            if (c instanceof com.patchlens.exception.GitHubApiException g && !g.isRetryable()) return false;
            if (c instanceof IllegalArgumentException) return false;
            if (c.getCause() == c) break;
        }
        return true;
    }

    // --- Message recoverer: mark job as DEAD_LETTER then reject without requeue ---
    @Bean
    public MessageRecoverer messageRecoverer(ReviewJobService reviewJobService,
                                             ObjectMapper objectMapper) {
        return (message, cause) -> {
            com.patchlens.observability.PatchLensMetrics.increment(
                    com.patchlens.observability.PatchLensMetrics.JOB_DEAD_LETTERED,
                    "cause", rootCause(cause).getClass().getSimpleName());
            try {
                ReviewJobMessage msg = objectMapper.readValue(message.getBody(), ReviewJobMessage.class);
                reviewJobService.markDeadLetter(msg.jobId(), rootCause(cause).getMessage());
            } catch (Exception e) {
                // Best-effort — the message must still be rejected regardless
            }
            // Throw so Spring AMQP nacks the message → routes to DLQ
            throw new AmqpRejectAndDontRequeueException("Max retries exhausted", cause);
        };
    }

    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c;
    }
}
