package com.example.aidocumentplatform.config;

import com.example.aidocumentplatform.security.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置类。
 *
 * 核心策略：
 *   1. 关闭 CSRF（前后端分离 + JWT 无需 CSRF 防护）
 *   2. 无状态会话（不使用 HttpSession，每次请求独立认证）
 *   3. 白名单：/api/auth/** 和 /api/health 无需认证即可访问
 *   4. 其余所有接口必须携带有效 JWT，否则返回 401
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /**
     * 配置安全过滤链。
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 关闭 CSRF —— JWT 模式下不需要
            .csrf(AbstractHttpConfigurer::disable)

            // 无状态会话 —— 不创建 HttpSession，每次请求独立认证
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 接口权限配置
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/error").permitAll()
                // 白名单：认证相关接口 + 健康检查
                .requestMatchers("/api/auth/**", "/api/health").permitAll()
                // 其余所有接口必须认证
                .anyRequest().authenticated()
            )

            // 在 UsernamePasswordAuthenticationFilter 之前插入 JWT 过滤器
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 暴露 AuthenticationManager Bean，供其他地方（如登录逻辑）使用。
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
