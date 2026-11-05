package com.patchlens.config;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/** Request factories with explicit connect and read timeouts for outbound REST clients. */
public final class HttpTimeouts {

    private HttpTimeouts() {}

    public static ClientHttpRequestFactory factory(Duration connect, Duration read) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connect)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(client);
        f.setReadTimeout(read);
        return f;
    }
}
