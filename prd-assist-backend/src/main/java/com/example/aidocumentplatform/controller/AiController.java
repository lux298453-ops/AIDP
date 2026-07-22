package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.service.AiService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @PostMapping("/prd/generate")
    public ApiResponse<String> generatePrd(@RequestBody Map<String, String> body) {
        String result = aiService.generatePrd(body.getOrDefault("inputData", ""));
        return ApiResponse.success(result);
    }

    @PostMapping("/prd/enhance")
    public ApiResponse<String> enhancePrd(@RequestBody Map<String, String> body) {
        String result = aiService.enhancePrd(
                body.getOrDefault("prdContent", ""),
                body.getOrDefault("instruction", ""));
        return ApiResponse.success(result);
    }

    @PostMapping("/prd/review")
    public ApiResponse<String> reviewPrd(@RequestBody Map<String, String> body) {
        String result = aiService.reviewPrd(body.getOrDefault("prdContent", ""));
        return ApiResponse.success(result);
    }

    @PostMapping("/prototype/generate")
    public ApiResponse<String> generatePrototype(@RequestBody Map<String, String> body) {
        String result = aiService.generatePrototype(body.getOrDefault("prdContent", ""));
        return ApiResponse.success(result);
    }
}
