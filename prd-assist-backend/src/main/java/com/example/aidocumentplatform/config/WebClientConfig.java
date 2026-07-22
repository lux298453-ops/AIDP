package com.example.aidocumentplatform.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * WebClient 全局配置。
 *
 * 针对大模型 API 调用场景：
 *   - 响应时间可能较长（30s~120s），设置较长的读取超时
 *   - 内存缓冲 16MB 以应对大 JSON 响应
 *   - 连接超时独立配置
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        // 构建底层 Netty HttpClient，配置超时参数
        HttpClient httpClient = HttpClient.create()
                // 连接超时: 10 秒
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                // 响应超时: 120 秒（大模型生成可能很慢）
                .responseTimeout(Duration.ofSeconds(120))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(120))
                           .addHandlerLast(new WriteTimeoutHandler(30))
                );

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // 内存缓冲区: 16MB，足够容纳大模型返回的 JSON
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}
