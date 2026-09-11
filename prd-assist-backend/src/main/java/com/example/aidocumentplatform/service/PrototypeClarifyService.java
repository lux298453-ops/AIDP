package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.request.PrototypeClarifyRequest;
import com.example.aidocumentplatform.model.dto.request.PrototypeFinalizeRequest;
import com.example.aidocumentplatform.model.dto.response.PrototypeClarifyResponse;

public interface PrototypeClarifyService {

    PrototypeClarifyResponse clarify(PrototypeClarifyRequest request, Long userId);

    PrototypeClarifyResponse finalizePlan(PrototypeFinalizeRequest request, Long userId);
}
