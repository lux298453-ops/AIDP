package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.common.FileStorage;
import com.example.aidocumentplatform.model.dto.request.PrdEnhanceRequest;
import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.PrdEnhanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * PRD 增强接口。
 *
 * POST /api/prd/enhance        →  文本增强，返回 { taskId }
 * POST /api/prd/enhance/word   →  上传 Word 素材增强
 */
@Slf4j
@RestController
@RequestMapping("/api/prd")
@RequiredArgsConstructor
public class PrdEnhanceController {

    private final PrdEnhanceService prdEnhanceService;
    private final FileStorage fileStorage;

    /** 文本输入增强 */
    @PostMapping("/enhance")
    public ApiResponse<Map<String, Long>> enhance(@RequestBody PrdEnhanceRequest request) {
        Long userId = getCurrentUserId();
        Long taskId = prdEnhanceService.submit(request, userId);
        return ApiResponse.success(Map.of("taskId", taskId));
    }

    /** Word 素材上传增强 */
    @PostMapping("/enhance/word")
    public ApiResponse<Map<String, Long>> enhanceWithWord(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "prdContent", required = false) String prdContent,
            @RequestParam(value = "prdDocumentId", required = false) Long prdDocumentId,
            @RequestParam(value = "contentTypes", required = false) String contentTypes,
            @RequestParam(value = "instruction", required = false) String instruction
    ) {
        Long userId = getCurrentUserId();
        try {
            byte[] fileBytes = file.getBytes();
            String fileName = file.getOriginalFilename();
            fileStorage.store(fileBytes, fileName);

            PrdEnhanceRequest req = new PrdEnhanceRequest();
            req.setPrdContent(prdContent);
            req.setPrdDocumentId(prdDocumentId);
            req.setInstruction(instruction);
            if (contentTypes != null) {
                req.setContentTypes(java.util.Arrays.asList(contentTypes.split(",")));
            }

            Long taskId = prdEnhanceService.submitWithWord(req, fileBytes, fileName, userId);
            return ApiResponse.success(Map.of("taskId", taskId));
        } catch (Exception e) {
            log.error("Word 增强请求失败", e);
            throw new RuntimeException("处理失败: " + e.getMessage());
        }
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
