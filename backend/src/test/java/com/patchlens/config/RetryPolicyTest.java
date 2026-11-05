package com.patchlens.config;

import com.patchlens.exception.GitHubApiException;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Measures the real attempt counts of the listener retry advice (Spring AMQP 4 / Spring core retry). */
class RetryPolicyTest {

    private final MessageRecoverer recoverer = mock(MessageRecoverer.class);
    private final MethodInterceptor interceptor = new RabbitMQConfig().retryInterceptor(recoverer, 3);

    private int attemptsUntilGivingUp(RuntimeException failure) {
        AtomicInteger calls = new AtomicInteger();
        Message message = new Message(new byte[0], new MessageProperties());
        MethodInvocation invocation = new MethodInvocation() {
            public Method getMethod() { try { return Object.class.getMethod("toString"); } catch (NoSuchMethodException e) { throw new IllegalStateException(e); } }
            public Object[] getArguments() { return new Object[]{null, message}; }
            public Object proceed() { calls.incrementAndGet(); throw failure; }
            public Object getThis() { return this; }
            public AccessibleObject getStaticPart() { return getMethod(); }
        };
        catchThrowable(() -> interceptor.invoke(invocation));
        return calls.get();
    }

    @Test
    void transientFailureIsAttemptedExactlyThreeTimesThenRecovered() {
        int attempts = attemptsUntilGivingUp(new ListenerExecutionFailedException("x", new IllegalStateException("db down")));
        assertThat(attempts).isEqualTo(RabbitMQConfig.DEFAULT_MAX_ATTEMPTS);
        verify(recoverer).recover(any(Message.class), any());
    }

    @Test
    void permanentFailuresAreNotRetried() {
        assertThat(attemptsUntilGivingUp(new ListenerExecutionFailedException("x",
                new AmqpRejectAndDontRequeueException("GitHub 404")))).isEqualTo(1);
        assertThat(attemptsUntilGivingUp(new ListenerExecutionFailedException("x",
                new GitHubApiException("PR_NOT_FOUND", "gone", 404)))).isEqualTo(1);
        assertThat(attemptsUntilGivingUp(new ListenerExecutionFailedException("x",
                new IllegalArgumentException("ReviewJob not found")))).isEqualTo(1);
    }

    @Test
    void retryableGitHubErrorsAreRetried() {
        assertThat(RabbitMQConfig.isRetryable(new GitHubApiException("GITHUB_API_ERROR", "bad gateway", 502))).isTrue();
        assertThat(RabbitMQConfig.isRetryable(new GitHubApiException("GITHUB_RATE_LIMIT", "slow down", 403))).isTrue();
        assertThat(RabbitMQConfig.isRetryable(new GitHubApiException("GITHUB_API_ERROR", "unauthorized", 401))).isFalse();
    }
}
