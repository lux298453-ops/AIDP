package com.example.aidocumentplatform.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的文档列表 — 统一视图对象。
 * 聚合 prd_document / prototype_result / review_report 三张表的数据。
 */
@Data
@Builder
@AllArgsConstructor
public class DocumentVO {

    /** 文档唯一标识（原始表ID，跨表可能重复，前端用 type+id 作为 key） */
    private Long id;

    /** 文档类型：PRD / PROTOTYPE / REVIEW */
    private String docType;

    /** 标题（PRD: title, Prototype: 拼接名, Review: 关联PRD title） */
    private String title;

    /** 描述或摘要 */
    private String description;

    /** 任务类型（与 async_task.task_type 对应） */
    private String taskType;

    /** 关联的任务 ID */
    private Long taskId;

    /** 关联的 PRD 文档 ID（原型/审查指向的原始PRD） */
    private Long prdDocumentId;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
