package com.example.aidocumentplatform.security;

import com.example.aidocumentplatform.model.entity.User;
import com.example.aidocumentplatform.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Spring Security 的 UserDetailsService 实现。
 *
 * 在 JWT 认证流程中，过滤器从 Token 中解析出用户名后，
 * 调用本服务从数据库加载完整的用户信息，用于构建 Authentication 对象。
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * 根据用户名加载用户信息。
     *
     * @param username 登录用户名
     * @return SecurityUser（实现了 UserDetails 接口）
     * @throws UsernameNotFoundException 如果用户不存在
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));

        // 将数据库中的 User 实体转换为 Spring Security 能识别的 SecurityUser
        return new SecurityUser(user.getId(), user.getUsername(), user.getPassword());
    }
}
