package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.PrototypeAssetPlan;

public interface PrototypeImageService {

    GeneratedImage generate(PrototypeAssetPlan plan, Long userId);

    record GeneratedImage(byte[] bytes, String mimeType, String fileExtension) {
    }
}
