package com.example.aidocumentplatform.model.enums;

public enum TaskType {
    PRD_GENERATE,
    PRD_ENHANCE,
    PROTOTYPE,
    PRD_REVIEW,
    /** 根据审查问题 AI 修订 PRD，产出新版 prd_document */
    PRD_REVIEW_FIX
}
