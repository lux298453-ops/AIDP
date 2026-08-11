package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.exception.BusinessException;
import com.example.aidocumentplatform.exception.ErrorCode;
import com.example.aidocumentplatform.model.dto.request.LoginRequest;
import com.example.aidocumentplatform.model.dto.request.RegisterRequest;
import com.example.aidocumentplatform.model.dto.response.LoginResponse;
import com.example.aidocumentplatform.model.entity.AiModelConfig;
import com.example.aidocumentplatform.model.entity.User;
import com.example.aidocumentplatform.repository.AiModelConfigRepository;
import com.example.aidocumentplatform.repository.UserRepository;
import com.example.aidocumentplatform.security.JwtUtil;
import com.example.aidocumentplatform.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务实现。
 *
 * 注册流程: 校验用户名唯一性 → BCrypt 加密密码 → 入库
 * 登录流程: 查用户 → BCrypt 比对密码 → 签发 JWT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AiModelConfigRepository aiModelConfigRepository;

    /**
     * 用户注册。
     *
     * @param request 包含 username、password、nickname
     * @throws BusinessException 如果用户名已存在
     */
    @Override
    @Transactional
    public void register(RegisterRequest request) {
        // 1. 校验用户名是否已被占用
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        // 2. BCrypt 加密密码后构建用户实体
        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname())
                .build();

        // 3. 持久化到数据库
        userRepository.save(user);
        log.info("用户注册成功: username={}", user.getUsername());

        // 4. 初始化 AI 配置：复制平台默认配置，保证新用户开箱即用
        try {
            aiModelConfigRepository.findFirstByEnabledTrueOrderByIdAsc()
                    .ifPresent(tpl -> {
                        AiModelConfig cfg = AiModelConfig.builder()
                                .userId(user.getId())
                                .provider(tpl.getProvider())
                                .baseUrl(tpl.getBaseUrl())
                                .apiKey(tpl.getApiKey())
                                .model(tpl.getModel())
                                .apiType(tpl.getApiType())
                                .appendApiPath(tpl.getAppendApiPath())
                                .openAiAuthEnabled(tpl.getOpenAiAuthEnabled())
                                .authHeaderType(tpl.getAuthHeaderType())
                                .actorAuthorization(tpl.getActorAuthorization())
                                .reasoningEffort(tpl.getReasoningEffort())
                                .disableResponseStorage(tpl.getDisableResponseStorage())
                                .maxOutputTokens(tpl.getMaxOutputTokens())
                                .imageDetail(tpl.getImageDetail())
                                .enabled(true)
                                .build();
                        aiModelConfigRepository.save(cfg);
                        log.info("已为新用户初始化 AI 配置: userId={}, templateUserId={}", user.getId(), tpl.getUserId());
                    });
        } catch (Exception e) {
            log.warn("初始化 AI 配置失败（不影响注册）: {}", e.getMessage());
        }
    }

    /**
     * 用户登录。
     *
     * @param request 包含 username、password
     * @return LoginResponse 包含 JWT token、userId、username、nickname
     * @throws BusinessException 如果用户名或密码错误
     */
    @Override
    public LoginResponse login(LoginRequest request) {
        // 1. 根据用户名查询用户
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        // 2. BCrypt 比对密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        // 3. 签发 JWT token
        String token = jwtUtil.generateToken(user.getUsername());

        log.info("用户登录成功: username={}", user.getUsername());

        // 4. 构建登录响应
        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .build();
    }
}
