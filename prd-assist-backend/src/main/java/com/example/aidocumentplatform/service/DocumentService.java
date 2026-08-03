package com.example.aidocumentplatform.service;

import com.example.aidocumentplatform.model.dto.response.DocumentVO;
import com.example.aidocumentplatform.model.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {

    Document upload(MultipartFile file, Long userId);

    List<Document> listByUser(Long userId);

    Document getById(Long id, Long userId);

    /**
     * 聚合查询我的文档（PRD + 原型 + 审查报告）。
     *
     * @param userId   当前用户 ID
     * @param keyword  标题模糊搜索（可选）
     * @param taskType 按任务类型筛选（可选：PRD_GENERATE / PROTOTYPE / PRD_REVIEW）
     * @param pageable 分页参数
     * @return 分页后的统一文档列表
     */
    Page<DocumentVO> listUnified(Long userId, String keyword, String taskType, Pageable pageable);

    /**
     * 删除我的文档（PRD / 原型 / 审查报告），仅允许删除本人文档。
     *
     * @param userId  当前用户 ID
     * @param docType 文档类型：PRD / PROTOTYPE / REVIEW
     * @param id      文档 ID
     */
    void delete(Long userId, String docType, Long id);
}
