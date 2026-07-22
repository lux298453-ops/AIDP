package com.example.aidocumentplatform.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 登录成功响应体。
 */
@Data
@Builder
@AllArgsConstructor
public class LoginResponse {

    /** 签发的 JWT token，前端需在后续请求中携带（Authorization: Bearer <token>） */
    private String token;

    /** 用户 ID */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 显示昵称 */
    private String nickname;
}
