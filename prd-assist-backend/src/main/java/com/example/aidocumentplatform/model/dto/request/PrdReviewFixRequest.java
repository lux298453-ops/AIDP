package com.example.aidocumentplatform.model.dto.request;

import lombok.Data;

import java.util.List;

/**
 * PRD 审查修复请求。
 * <ul>
 *   <li>issueIndexes 优先：按报告 issues 数组下标勾选</li>
 *   <li>否则按 severities 过滤（默认 CRITICAL + MAJOR）</li>
 * </ul>
 */
@Data
public class PrdReviewFixRequest {

    /** 要修复的问题下标（0-based，对应报告 issues 数组） */
    private List<Integer> issueIndexes;

    /**
     * 按严重程度过滤；当 issueIndexes 为空时生效。
     * 默认 CRITICAL、MAJOR。
     */
    private List<String> severities;

    /**
     * Optional source PRD document id. When omitted, the PRD linked to the
     * review report is used. The PRD editor passes the current document id so
     * consecutive fixes build on the latest version.
     */
    private Long sourcePrdDocumentId;
}
