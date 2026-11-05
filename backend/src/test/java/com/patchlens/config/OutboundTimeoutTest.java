package com.patchlens.config;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** A stalled upstream (model API, GitHub) must not block a worker beyond the configured timeout. */
class OutboundTimeoutTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            try { Thread.sleep(20_000); } catch (InterruptedException ignored) {}
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void stalledModelRequestFailsAtTheConfiguredTimeout() {
        LangChain4jConfig config = new LangChain4jConfig();
        ReflectionTestUtils.setField(config, "apiKey", "test");
        ReflectionTestUtils.setField(config, "chatModelName", "gpt-4o-mini");
        ReflectionTestUtils.setField(config, "baseUrl", base + "/v1");
        ReflectionTestUtils.setField(config, "timeoutSeconds", 1);
        ReflectionTestUtils.setField(config, "maxRetries", 0);
        ReflectionTestUtils.setField(config, "temperature", 0.1);
        ReflectionTestUtils.setField(config, "maxTokens", 100);
        ChatModel model = config.chatModel();

        long start = System.nanoTime();
        Throwable t = catchThrowable(() -> model.chat("hello"));
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toSeconds();

        assertThat(t).isNotNull();
        assertThat(elapsed).isLessThan(10);
    }

    @Test
    void stalledRestCallFailsAtTheReadTimeout() {
        RestClient client = RestClient.builder()
                .requestFactory(HttpTimeouts.factory(Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .baseUrl(base).build();

        long start = System.nanoTime();
        Throwable t = catchThrowable(() -> client.get().uri("/repos/o/r").retrieve().body(String.class));
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toSeconds();

        assertThat(t).isNotNull();
        assertThat(elapsed).isLessThan(10);
    }
}
