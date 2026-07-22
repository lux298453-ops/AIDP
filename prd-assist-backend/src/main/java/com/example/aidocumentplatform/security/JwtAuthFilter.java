package com.example.aidocumentplatform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器 —— 每个请求进入时执行一次。
 *
 * 流程:
 *   1. 从 Authorization 头提取 Bearer token
 *   2. 校验 token 是否有效
 *   3. 有效 → 加载用户信息 → 写入 SecurityContext → 后续 Controller 可以直接获取当前用户
 *   4. 无效或缺失 → 直接交给后续过滤器链（SecurityConfig 会拒绝未认证请求，返回 401）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 从请求头中提取 token
        String token = extractToken(request);

        // 2. token 存在且有效时，设置认证信息
        if (StringUtils.hasText(token) && jwtUtil.validateToken(token)) {
            // 从 token 中获取用户名
            String username = jwtUtil.getUsernameFromToken(token);

            // 加载完整的用户信息
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            // 构建 Spring Security 的 Authentication 对象
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userDetails,          // principal → 可以在 Controller 中获取
                            null,                 // credentials → JWT 模式下无需密码
                            userDetails.getAuthorities()
                    );
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request)
            );

            // 3. 写入 SecurityContext，标记当前请求已认证
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("JWT 认证成功: username={}", username);
        }

        // 4. 继续执行过滤器链
        filterChain.doFilter(request, response);
    }

    /**
     * 从 HTTP 请求中提取 JWT Token。
     * 优先级：1) Authorization: Bearer <token>  2) ?token=<token>（SSE 兼容）
     *
     * @return token 字符串，没有则返回 null
     */
    private String extractToken(HttpServletRequest request) {
        // 方式一：Header（标准方式）
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // 方式二：Query 参数（SSE / EventSource 不支持自定义 Header）
        String tokenParam = request.getParameter("token");
        if (StringUtils.hasText(tokenParam)) {
            return tokenParam;
        }
        return null;
    }
}
