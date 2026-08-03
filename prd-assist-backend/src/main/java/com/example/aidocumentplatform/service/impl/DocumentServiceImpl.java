package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.exception.BusinessException;
import com.example.aidocumentplatform.exception.ErrorCode;
import com.example.aidocumentplatform.model.dto.response.DocumentVO;
import com.example.aidocumentplatform.model.entity.Document;
import com.example.aidocumentplatform.model.entity.PrdDocument;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.model.entity.ReviewReport;
import com.example.aidocumentplatform.repository.*;
import com.example.aidocumentplatform.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;
    private final PrdDocumentRepository prdDocumentRepository;
    private final PrototypeResultRepository prototypeResultRepository;
    private final ReviewReportRepository reviewReportRepository;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    // ==================== 文件上传 ====================
    @Override
    public Document upload(MultipartFile file, Long userId) { return null; }

    @Override
    public List<Document> listByUser(Long userId) { return documentRepository.findByUserIdOrderByCreatedAtDesc(userId); }

    @Override
    public Document getById(Long id, Long userId) {
        Document doc = documentRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (!doc.getUserId().equals(userId)) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        return doc;
    }

    // ==================== 聚合查询 ====================
    @Override
    public Page<DocumentVO> listUnified(Long userId, String keyword, String taskType, Pageable pageable) {
        List<DocumentVO> all = new ArrayList<>();

        // PRD 文档
        if (matches(taskType, "PRD_GENERATE")) {
            List<PrdDocument> prds = prdDocumentRepository.findByUserIdOrderByCreatedAtDesc(userId);
            for (PrdDocument p : prds) {
                if (keyword != null && !keyword.isBlank() && !p.getTitle().contains(keyword)) continue;
                all.add(DocumentVO.builder().id(p.getId()).docType("PRD").title(p.getTitle())
                        .description(p.getDescription()).taskType("PRD_GENERATE")
                        .taskId(p.getTaskId()).prdDocumentId(null).createdAt(p.getCreatedAt()).build());
            }
        }

        // 原型结果
        if (matches(taskType, "PROTOTYPE")) {
            List<PrototypeResult> protos = prototypeResultRepository.findByUserIdOrderByCreatedAtDesc(userId);
            for (PrototypeResult pt : protos) {
                String title = "原型图 (" + pt.getPlatform().name() + ")";
                if (keyword != null && !keyword.isBlank() && !title.contains(keyword)) continue;
                all.add(DocumentVO.builder().id(pt.getId()).docType("PROTOTYPE").title(title)
                        .description(null).taskType("PROTOTYPE")
                        .taskId(pt.getTaskId()).prdDocumentId(pt.getPrdDocumentId())
                        .createdAt(pt.getCreatedAt()).build());
            }
        }

        // 审查报告
        if (matches(taskType, "PRD_REVIEW")) {
            List<ReviewReport> reviews = reviewReportRepository.findByUserIdOrderByCreatedAtDesc(userId);
            for (ReviewReport r : reviews) {
                String title = "审查报告 #" + r.getId();
                if (keyword != null && !keyword.isBlank() && !title.contains(keyword)) continue;
                all.add(DocumentVO.builder().id(r.getId()).docType("REVIEW").title(title)
                        .description(null).taskType("PRD_REVIEW")
                        .taskId(r.getTaskId()).prdDocumentId(r.getPrdDocumentId())
                        .createdAt(r.getCreatedAt()).build());
            }
        }

        // 按时间倒序
        all.sort(Comparator.comparing(DocumentVO::getCreatedAt).reversed());

        // 手动分页
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), all.size());
        List<DocumentVO> pageContent = start < all.size() ? all.subList(start, end) : Collections.emptyList();
        return new PageImpl<>(pageContent, pageable, all.size());
    }

    private boolean matches(String filter, String value) {
        return filter == null || filter.isBlank() || filter.equals(value);
    }

    // ==================== 删除 ====================
    @Override
    public void delete(Long userId, String docType, Long id) {
        String type = docType == null ? "" : docType.toUpperCase();
        switch (type) {
            case "PRD" -> {
                PrdDocument prd = prdDocumentRepository.findById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
                if (!prd.getUserId().equals(userId)) throw new BusinessException(ErrorCode.ACCESS_DENIED);
                prdDocumentRepository.delete(prd);
            }
            case "PROTOTYPE" -> {
                PrototypeResult proto = prototypeResultRepository.findById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
                if (!proto.getUserId().equals(userId)) throw new BusinessException(ErrorCode.ACCESS_DENIED);
                prototypeResultRepository.delete(proto);
            }
            case "REVIEW" -> {
                ReviewReport review = reviewReportRepository.findById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
                if (!review.getUserId().equals(userId)) throw new BusinessException(ErrorCode.ACCESS_DENIED);
                reviewReportRepository.delete(review);
            }
            default -> throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
    }
}
