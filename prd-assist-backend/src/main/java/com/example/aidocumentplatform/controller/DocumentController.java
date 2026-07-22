package com.example.aidocumentplatform.controller;

import com.example.aidocumentplatform.model.dto.response.ApiResponse;
import com.example.aidocumentplatform.model.dto.response.DocumentVO;
import com.example.aidocumentplatform.model.dto.response.PagedResponse;
import com.example.aidocumentplatform.model.entity.Document;
import com.example.aidocumentplatform.security.SecurityUser;
import com.example.aidocumentplatform.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/upload")
    public ApiResponse<Document> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(documentService.upload(file, getCurrentUserId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Document> getById(@PathVariable Long id) {
        return ApiResponse.success(documentService.getById(id, getCurrentUserId()));
    }

    /**
     * 我的文档列表 — 聚合 PRD / 原型 / 审查报告。
     *
     * @param keyword  标题模糊搜索
     * @param taskType 按类型筛选: PRD_GENERATE / PROTOTYPE / PRD_REVIEW
     * @param page     页码（0 开始）
     * @param size     每页条数（默认 20）
     */
    @GetMapping
    public ApiResponse<PagedResponse<DocumentVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String taskType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DocumentVO> result = documentService.listUnified(getCurrentUserId(), keyword, taskType, pageable);
        PagedResponse<DocumentVO> paged = PagedResponse.<DocumentVO>builder()
                .content(result.getContent()).totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages()).number(result.getNumber()).size(result.getSize()).build();
        return ApiResponse.success(paged);
    }

    private Long getCurrentUserId() {
        SecurityUser user = (SecurityUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return user.getUserId();
    }
}
