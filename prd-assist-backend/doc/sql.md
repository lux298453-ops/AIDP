CREATE DATABASE `ai_document_platform` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ========================================
-- 用户表
-- ========================================
CREATE TABLE `user` (
    `id`            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `username`      VARCHAR(50)  NOT NULL COMMENT '登录用户名',
    `password`      VARCHAR(100) NOT NULL COMMENT 'BCrypt加密后的密码',
    `nickname`      VARCHAR(50)  NOT NULL COMMENT '显示名称',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表（无权限分级，所有人角色平等）';

-- ========================================
-- 异步任务表（PRD生成/增强/原型/审查 统一记录）
-- ========================================
CREATE TABLE `async_task` (
    `id`             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `user_id`        BIGINT UNSIGNED NOT NULL,
    `task_type`      VARCHAR(30)  NOT NULL COMMENT 'PRD_GENERATE/PRD_ENHANCE/PROTOTYPE/PRD_REVIEW',
    `status`         VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    `input_params`   JSON         NULL COMMENT '提交任务时的输入参数快照',
    `result_ref_id`  BIGINT UNSIGNED NULL COMMENT '成功后指向对应结果表的ID(prd_document/prototype_result/review_report)',
    `error_message`  VARCHAR(500) NULL COMMENT '失败时的错误信息',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY `idx_user_status` (`user_id`, `status`),
    CONSTRAINT `fk_task_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步任务状态表(替代Celery任务表)';

-- ========================================
-- PRD文档表
-- ========================================
CREATE TABLE `prd_document` (
    `id`               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `user_id`          BIGINT UNSIGNED NOT NULL,
    `task_id`          BIGINT UNSIGNED NULL COMMENT '关联生成该文档的任务，可空(如手动创建)',
    `title`            VARCHAR(50)  NOT NULL COMMENT '功能名称',
    `description`      VARCHAR(500) NULL COMMENT '原始需求描述',
    `content`          JSON         NOT NULL COMMENT '结构化PRD内容(章节数组)',
    `source_type`      VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/XMIND，标记是否由XMind解析生成',
    `xmind_file_url`   VARCHAR(255) NULL COMMENT '原始XMind文件的对象存储地址',
    `template`         VARCHAR(20)  NOT NULL DEFAULT 'STANDARD',
    `detail_level`      VARCHAR(20)  NOT NULL DEFAULT 'DETAILED' COMMENT 'CONCISE/DETAILED',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY `idx_user_created` (`user_id`, `created_at`),
    CONSTRAINT `fk_prd_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`),
    CONSTRAINT `fk_prd_task` FOREIGN KEY (`task_id`) REFERENCES `async_task`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PRD文档表';

-- ========================================
-- 原型结果表
-- ========================================
CREATE TABLE `prototype_result` (
    `id`               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `user_id`          BIGINT UNSIGNED NOT NULL,
    `prd_document_id`  BIGINT UNSIGNED NULL COMMENT '关联的PRD，可空(允许直接从文字描述生成)',
    `task_id`          BIGINT UNSIGNED NOT NULL,
    `prototype_type`   VARCHAR(20)  NOT NULL COMMENT 'SINGLE_PAGE/MULTI_PAGE',
    `platform`         VARCHAR(20)  NOT NULL DEFAULT 'APP' COMMENT '生成的原型终端类型',
    `content`          JSON         NOT NULL COMMENT '生成的页面代码/结构(多页面时为数组)',
    `reference_image_url` VARCHAR(255) NULL COMMENT '用户上传的风格参考图',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_user_created` (`user_id`, `created_at`),
    CONSTRAINT `fk_proto_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`),
    CONSTRAINT `fk_proto_prd` FOREIGN KEY (`prd_document_id`) REFERENCES `prd_document`(`id`),
    CONSTRAINT `fk_proto_task` FOREIGN KEY (`task_id`) REFERENCES `async_task`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原型生成结果表';

-- ========================================
-- PRD审查报告表
-- ========================================
CREATE TABLE `review_report` (
    `id`               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    `user_id`          BIGINT UNSIGNED NOT NULL,
    `prd_document_id`  BIGINT UNSIGNED NOT NULL,
    `task_id`          BIGINT UNSIGNED NOT NULL,
    `dimensions`       JSON         NOT NULL COMMENT '审查维度(完整性/一致性/合规性)',
    `issues`           JSON         NOT NULL COMMENT '问题列表[{severity, location, description, suggestion}]',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_prd` (`prd_document_id`),
    CONSTRAINT `fk_review_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`),
    CONSTRAINT `fk_review_prd` FOREIGN KEY (`prd_document_id`) REFERENCES `prd_document`(`id`),
    CONSTRAINT `fk_review_task` FOREIGN KEY (`task_id`) REFERENCES `async_task`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PRD审查报告表';