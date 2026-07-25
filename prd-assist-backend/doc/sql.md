-- ============================================================
-- 策划部 AI 辅助设计平台 — PostgreSQL 建表脚本
-- 数据库: ai_document_platform / PostgreSQL 18.4
-- 说明: 开发阶段由 JPA ddl-auto=update 自动建表，
--       此文件作为 SQL 参考，生产环境可用 Flyway 管理版本
--       所有表名、列名均使用小写（PostgreSQL 默认自动转为小写）
-- ============================================================

-- 手动建库（需要 superuser 权限）:
-- CREATE DATABASE ai_document_platform ENCODING 'UTF8';

-- ========================================
-- 1. 用户表 (users) — 使用 users 避开 PostgreSQL 保留字 user
-- ========================================
CREATE TABLE IF NOT EXISTS users (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(50)  NOT NULL,
    password   VARCHAR(100) NOT NULL,
    nickname   VARCHAR(50)  NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 注释（PostgreSQL 使用 COMMENT ON 语法）
COMMENT ON TABLE users IS '用户表（无权限分级，所有人角色平等）';
COMMENT ON COLUMN users.username IS '登录用户名';
COMMENT ON COLUMN users.password IS 'BCrypt加密后的密码';
COMMENT ON COLUMN users.nickname IS '显示名称';

-- 唯一约束
CREATE UNIQUE INDEX IF NOT EXISTS uk_username ON users (username);

-- 自动更新 updated_at 的触发器函数（所有表共用）
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 为 user 表创建触发器
CREATE TRIGGER trg_user_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ========================================
-- 2. 异步任务表
-- ========================================
CREATE TABLE IF NOT EXISTS async_task (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    task_type     VARCHAR(30)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    input_params  JSONB        NULL,
    result_ref_id BIGINT       NULL,
    error_message VARCHAR(500) NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_task_user FOREIGN KEY (user_id) REFERENCES users(id)
);

COMMENT ON TABLE async_task IS '异步任务状态表';
COMMENT ON COLUMN async_task.task_type IS 'PRD_GENERATE/PRD_ENHANCE/PROTOTYPE/PRD_REVIEW';
COMMENT ON COLUMN async_task.status IS 'PENDING/RUNNING/SUCCESS/FAILED';
COMMENT ON COLUMN async_task.input_params IS '提交任务时的输入参数快照';
COMMENT ON COLUMN async_task.result_ref_id IS '成功后指向对应结果表的ID';
COMMENT ON COLUMN async_task.error_message IS '失败时的错误信息';

CREATE INDEX IF NOT EXISTS idx_task_user_status ON async_task (user_id, status);

CREATE TRIGGER trg_async_task_updated_at
    BEFORE UPDATE ON async_task
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ========================================
-- 3. PRD文档表
-- ========================================
CREATE TABLE IF NOT EXISTS prd_document (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    task_id        BIGINT       NULL,
    title          VARCHAR(50)  NOT NULL,
    description    VARCHAR(15000) NULL,
    content        JSONB        NOT NULL,
    source_type    VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    xmind_file_url VARCHAR(255) NULL,
    template       VARCHAR(20)  NOT NULL DEFAULT 'STANDARD',
    detail_level   VARCHAR(20)  NOT NULL DEFAULT 'DETAILED',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_prd_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_prd_task FOREIGN KEY (task_id) REFERENCES async_task(id),
    CONSTRAINT prd_document_template_check CHECK (template IN ('STANDARD', 'CUSTOM'))
);

COMMENT ON TABLE prd_document IS 'PRD文档表';
COMMENT ON COLUMN prd_document.task_id IS '关联生成该文档的任务';
COMMENT ON COLUMN prd_document.title IS '功能名称';
COMMENT ON COLUMN prd_document.description IS '原始需求描述';
COMMENT ON COLUMN prd_document.content IS '结构化PRD内容(章节数组)';
COMMENT ON COLUMN prd_document.source_type IS 'MANUAL/XMIND';
COMMENT ON COLUMN prd_document.xmind_file_url IS '原始XMind文件的对象存储地址';
COMMENT ON COLUMN prd_document.detail_level IS 'CONCISE/DETAILED';

CREATE INDEX IF NOT EXISTS idx_prd_user_created ON prd_document (user_id, created_at);

CREATE TRIGGER trg_prd_document_updated_at
    BEFORE UPDATE ON prd_document
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ========================================
-- 4. 原型结果表
-- ========================================
CREATE TABLE IF NOT EXISTS prototype_result (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    prd_document_id     BIGINT       NULL,
    task_id             BIGINT       NOT NULL,
    prototype_type      VARCHAR(20)  NOT NULL,
    platform            VARCHAR(20)  NOT NULL DEFAULT 'APP',
    content             TEXT         NOT NULL,
    reference_image_url VARCHAR(255) NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_proto_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_proto_prd FOREIGN KEY (prd_document_id) REFERENCES prd_document(id),
    CONSTRAINT fk_proto_task FOREIGN KEY (task_id) REFERENCES async_task(id)
);

COMMENT ON TABLE prototype_result IS '原型生成结果表';
COMMENT ON COLUMN prototype_result.prototype_type IS 'SINGLE_PAGE/MULTI_PAGE';
COMMENT ON COLUMN prototype_result.platform IS '生成的原型终端类型';
COMMENT ON COLUMN prototype_result.content IS '生成的页面代码/结构';
COMMENT ON COLUMN prototype_result.reference_image_url IS '用户上传的风格参考图';

CREATE INDEX IF NOT EXISTS idx_proto_user_created ON prototype_result (user_id, created_at);

-- ========================================
-- 5. PRD审查报告表
-- ========================================
CREATE TABLE IF NOT EXISTS review_report (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT    NOT NULL,
    prd_document_id BIGINT    NOT NULL,
    task_id         BIGINT    NOT NULL,
    dimensions      JSONB     NOT NULL,
    issues          JSONB     NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_review_prd FOREIGN KEY (prd_document_id) REFERENCES prd_document(id),
    CONSTRAINT fk_review_task FOREIGN KEY (task_id) REFERENCES async_task(id)
);

COMMENT ON TABLE review_report IS 'PRD审查报告表';
COMMENT ON COLUMN review_report.dimensions IS '审查维度(完整性/一致性/合规性)';
COMMENT ON COLUMN review_report.issues IS '问题列表[{severity, location, description, suggestion}]';

CREATE INDEX IF NOT EXISTS idx_review_prd ON review_report (prd_document_id);

-- ========================================
-- 6. 文档表（文件上传记录）
-- ========================================
CREATE TABLE IF NOT EXISTS documents (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    file_name      VARCHAR(256) NOT NULL,
    file_type      VARCHAR(32)  NOT NULL,
    file_path      VARCHAR(512) NULL,
    file_size      BIGINT       NOT NULL,
    parsed_content JSONB        NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_doc_user FOREIGN KEY (user_id) REFERENCES users(id)
);

COMMENT ON TABLE documents IS '文件上传记录表';
COMMENT ON COLUMN documents.file_name IS '原始文件名';
COMMENT ON COLUMN documents.file_type IS '文件类型扩展名';
COMMENT ON COLUMN documents.file_path IS '对象存储路径';
COMMENT ON COLUMN documents.parsed_content IS '解析后的内容（JSON格式）';

-- ========================================
-- 7. 任务表（旧版兼容，如不再使用可删除）
-- ========================================
CREATE TABLE IF NOT EXISTS tasks (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    task_type  VARCHAR(32)  NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    progress   INT          NOT NULL DEFAULT 0,
    input_data JSONB        NULL,
    output_data JSONB       NULL,
    error_msg  VARCHAR(1024) NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tasks_user FOREIGN KEY (user_id) REFERENCES users(id)
);

COMMENT ON TABLE tasks IS '任务表（旧版兼容）';

CREATE TRIGGER trg_tasks_updated_at
    BEFORE UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- 迁移脚本（按时间倒序，最新在前）
-- ============================================================

-- 2026-07-22: prototype_result.content 从 JSONB 改为 TEXT
-- 原因：原型生成的内容是 HTML 页面代码，不是 JSON，JSONB 会拒绝存储
ALTER TABLE prototype_result ALTER COLUMN content TYPE TEXT;

-- 2026-07-22: prd_document.description 从 VARCHAR(2000) 扩展到 VARCHAR(15000)
-- 原因：PRD 生成输入描述从 500 字放宽到 15000 字
ALTER TABLE prd_document ALTER COLUMN description TYPE VARCHAR(15000);

-- 2026-07-22: prd_document.template 允许 CUSTOM（自定义模板）
-- 原因：Hibernate/历史建表生成的 check 仅允许 STANDARD，导致自定义模板保存失败
ALTER TABLE prd_document DROP CONSTRAINT IF EXISTS prd_document_template_check;
ALTER TABLE prd_document ADD CONSTRAINT prd_document_template_check
  CHECK (template IN ('STANDARD', 'CUSTOM'));

-- 2026-07-23: async_task.task_type 允许 PRD_REVIEW_FIX
-- 原因：审查问题 AI 修订产出新版 PRD
ALTER TABLE async_task DROP CONSTRAINT IF EXISTS async_task_task_type_check;
ALTER TABLE async_task ADD CONSTRAINT async_task_task_type_check
  CHECK (task_type IN ('PRD_GENERATE','PRD_ENHANCE','PROTOTYPE','PRD_REVIEW','PRD_REVIEW_FIX'));
