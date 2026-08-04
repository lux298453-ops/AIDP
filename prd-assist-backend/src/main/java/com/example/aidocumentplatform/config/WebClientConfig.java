package com.example.aidocumentplatform.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.Arrays;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(
            @Value("${app.http.tls-protocols:TLSv1.3,TLSv1.2}") String tlsProtocols,
            @Value("${app.http.connect-timeout-ms:10000}") int connectTimeoutMs,
            @Value("${app.http.ssl-handshake-timeout-seconds:30}") long sslHandshakeTimeoutSeconds,
            @Value("${app.http.response-timeout-seconds:300}") long responseTimeoutSeconds,
            @Value("${app.http.read-timeout-seconds:300}") int readTimeoutSeconds,
            @Value("${app.http.write-timeout-seconds:60}") int writeTimeoutSeconds,
            @Value("${app.http.max-in-memory-size-mb:16}") int maxInMemorySizeMb) throws SSLException {
        String[] protocols = parseTlsProtocols(tlsProtocols);
        SslContext sslContext = SslContextBuilder.forClient().protocols(protocols).build();

        HttpClient httpClient = HttpClient.create()
                .protocol(HttpProtocol.HTTP11)
                .secure(ssl -> ssl
                        .sslContext(sslContext)
                        .handshakeTimeout(Duration.ofSeconds(sslHandshakeTimeoutSeconds)))
                .keepAlive(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(readTimeoutSeconds))
                        .addHandlerLast(new WriteTimeoutHandler(writeTimeoutSeconds)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(maxInMemorySizeMb * 1024 * 1024))
                .build();
    }

    private String[] parseTlsProtocols(String tlsProtocols) {
        if (tlsProtocols == null || tlsProtocols.isBlank()) {
            return new String[]{"TLSv1.3", "TLSv1.2"};
        }
        return Arrays.stream(tlsProtocols.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);
    }
}