package com.example.aidocumentplatform.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求体。
 */
@Data
public class RegisterRequest {

    /** 登录用户名，全局唯一 */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度3-32个字符")
    private String username;

    /** 登录密码，至少6位，入库前由 BCrypt 加密 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度6-64个字符")
    private String password;

    /** 显示昵称 */
    @NotBlank(message = "昵称不能为空")
    @Size(max = 50, message = "昵称最长50个字符")
    private String nickname;
}
