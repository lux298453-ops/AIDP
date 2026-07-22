package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.LoginRequest;
import com.example.aidocumentplatform.model.dto.request.RegisterRequest;
import com.example.aidocumentplatform.model.dto.response.LoginResponse;

public interface AuthService {

    void register(RegisterRequest request);

    LoginResponse login(LoginRequest request);
}
