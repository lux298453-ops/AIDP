package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.common.FileStorage;
import com.example.aidocumentplatform.exception.BusinessException;
import com.example.aidocumentplatform.exception.ErrorCode;
import com.example.aidocumentplatform.model.dto.response.DocumentVO;
import com.example.aidocumentplatform.model.entity.Document;
import com.example.aidocumentplatform.model.entity.PrdDocument;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.model.entity.ReviewReport;
import com.example.aidocumentplatform.repository.DocumentRepository;
import com.example.aidocumentplatform.repository.PrdDocumentRepository;
import com.example.aidocumentplatform.repository.PrototypeResultRepository;
import com.example.aidocumentplatform.repository.ReviewReportRepository;
import com.example.aidocumentplatform.service.DocumentService;
import com.example.aidocumentplatform.util.WordReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private static final int MAX_PARSED_TEXT_LENGTH = 20000;

    private final DocumentRepository documentRepository;
    private final PrdDocumentRepository prdDocumentRepository;
    private final PrototypeResultRepository prototypeResultRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final FileStorage fileStorage;
    private final WordReader wordReader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Document upload(MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "上传文件不能为空");
        }
        String originalFileName = StringUtils.hasText(file.getOriginalFilename())
                ? StringUtils.cleanPath(file.getOriginalFilename())
                : "document.bin";
        try {
            byte[] bytes = file.getBytes();
            String filePath = fileStorage.store(bytes, originalFileName);
            String fileType = detectFileType(originalFileName, file.getContentType());
            Document document = Document.builder()
                    .userId(userId)
                    .fileName(originalFileName)
                    .fileType(fileType)
                    .filePath(filePath)
                    .fileSize(file.getSize())
                    .parsedContent(buildParsedContent(originalFileName, fileType, bytes))
                    .build();
            return documentRepository.save(document);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("文件上传失败: userId={}, fileName={}", userId, originalFileName, e);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public List<Document> listByUser(Long userId) {
        return documentRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    public Document getById(Long id, Long userId) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (!doc.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return doc;
    }

    @Override
    public Page<DocumentVO> listUnified(Long userId, String keyword, String taskType, Pageable pageable) {
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        String normalizedTaskType = taskType == null || taskType.isBlank() ? null : taskType.trim().toUpperCase();
        return documentRepository.findUnifiedDocuments(userId, normalizedKeyword, normalizedTaskType, pageable)
                .map(item -> DocumentVO.builder()
                        .id(item.getId())
                        .docType(item.getDocType())
                        .title(item.getTitle())
                        .description(item.getDescription())
                        .taskType(item.getTaskType())
                        .taskId(item.getTaskId())
                        .prdDocumentId(item.getPrdDocumentId())
                        .createdAt(item.getCreatedAt())
                        .build());
    }

    @Override
    public void delete(Long userId, String docType, Long id) {
        String type = docType == null ? "" : docType.toUpperCase();
        switch (type) {
            case "PRD" -> {
                PrdDocument prd = prdDocumentRepository.findById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
                if (!prd.getUserId().equals(userId)) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED);
                }
                prdDocumentRepository.delete(prd);
            }
            case "PROTOTYPE" -> {
                PrototypeResult proto = prototypeResultRepository.findById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
                if (!proto.getUserId().equals(userId)) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED);
                }
                prototypeResultRepository.delete(proto);
            }
            case "REVIEW" -> {
                ReviewReport review = reviewReportRepository.findById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
                if (!review.getUserId().equals(userId)) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED);
                }
                reviewReportRepository.delete(review);
            }
            default -> throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
    }

    private String detectFileType(String fileName, String contentType) {
        if (StringUtils.hasText(fileName)) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                return fileName.substring(dot + 1).toLowerCase();
            }
        }
        if (StringUtils.hasText(contentType)) {
            int slash = contentType.lastIndexOf('/');
            return slash >= 0 && slash < contentType.length() - 1
                    ? contentType.substring(slash + 1).toLowerCase()
                    : contentType.toLowerCase();
        }
        return "bin";
    }

    private String buildParsedContent(String fileName, String fileType, byte[] bytes) {
        String normalizedType = fileType == null ? "" : fileType.toLowerCase();
        try {
            String extractedText = switch (normalizedType) {
                case "docx" -> wordReader.extractText(bytes);
                case "txt", "md", "markdown", "json", "csv", "xml", "html" -> new String(bytes, StandardCharsets.UTF_8);
                default -> null;
            };
            if (extractedText == null || extractedText.isBlank()) {
                return null;
            }
            String trimmed = extractedText.length() > MAX_PARSED_TEXT_LENGTH
                    ? extractedText.substring(0, MAX_PARSED_TEXT_LENGTH)
                    : extractedText;
            return objectMapper.writeValueAsString(Map.of(
                    "fileName", fileName,
                    "fileType", normalizedType,
                    "text", trimmed
            ));
        } catch (Exception e) {
            log.warn("解析上传文档内容失败: fileName={}, cause={}", fileName, e.getMessage());
            return null;
        }
    }
}