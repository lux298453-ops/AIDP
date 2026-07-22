# 任务本

> 最后更新: 2026-07-22

---

## 一、阻塞项（必须完成）

### 1. 配置 AI API Key
- [ ] 在 `application-dev.yml` 中填写真实的 `app.ai.api-key`
- [ ] 验证 Claude API 调用能成功返回（`POST /api/ai/test`）
- [ ] 验证 PRD 生成全链路跑通（PENDING → RUNNING → SUCCESS）
- [ ] 验证所有异步模块（生成/增强/审查/原型）均能 SUCCESS
- **当前状态**: 占位符 `请替换为你的AI_API_KEY`，所有 AI 调用均返回 FAILED

### 2. 文件存储切换 ⏸️ 暂缓
- [ ] 将 `LocalFileStorage` 替换为 `MinioFileStorage`
- [ ] 或对接阿里云 OSS / 腾讯云 COS
- [ ] 实现 `FileStorage` 接口的新实现类即可，业务代码无需改动
- **当前状态**: 使用本地文件系统 `uploads/` 目录，满足开发需求
- **暂缓原因**: 上线前切换即可，不影响功能开发

### 3. 数据库连接确认
- [x] PostgreSQL 可用性验证（`ai_document_platform`）
- [x] 注册登录全链路写入/读取验证
- [x] PostgreSQL 测试数据库可用

---

## 二、部署与环境 ⏸️ 暂缓

### 4. Docker 容器化
- [ ] 编写后端 `Dockerfile`（多阶段构建，打包 Spring Boot jar）
- [ ] 编写前端 `Dockerfile`（Nginx 托管静态文件）
- [ ] 编写 `docker-compose.yml`（PostgreSQL + 后端 + 前端）
- [ ] 添加健康检查和启动依赖顺序
- **参考**: 技术选型方案 第四节
- **暂缓原因**: 10~15 人内部系统，开发阶段直接 `mvn spring-boot:run` + `npm run dev` 即可

### 5. CI 流水线
- [ ] GitHub Actions / GitLab CI：`mvn test` + Docker 镜像构建
- [ ] 前端 `npm run build` 校验
- **参考**: 技术选型方案 第四节
- **暂缓原因**: 小团队内部工具，手动构建成本可接受

---

## 三、功能模块

### 6. PRD 增强流程
- [x] `POST /api/prd/enhance` 接口
- [x] `POST /api/prd/enhance/word` Word 素材上传接口
- [x] `PrdEnhancePromptTemplate` 四类内容 prompt
- [x] `WordReader` Apache POI 提取 docx 文本
- [x] 前端 `PrdEnhanceView` 完整交互（粘贴/选择PRD + 4卡片 + Word上传 + SSE + 预览）
- [ ] 配置 AI Key 后跑通 SUCCESS 链路

### 7. PRD 审查流程
- [x] `POST /api/prd/review` 接口
- [x] `GET /api/review/{id}` 获取审查报告
- [x] `PrdReviewPromptTemplate` 三维度 prompt（完整性/一致性/合规性）
- [x] 前端 `PrdReviewView`（维度选择 + 严重程度分级报告）
- [ ] 配置 AI Key 后跑通 SUCCESS 链路

### 8. 原型生成流程
- [x] `POST /api/prototype/generate` 接口
- [x] `GET /api/prototype/{id}` 获取 HTML
- [x] `PrototypePromptTemplate` 单页面 + 多页面 prompt
- [x] 前端 `PrototypeView`（终端卡片 + iframe sandbox + 多页缩略图导航）
- [ ] 配置 AI Key 后跑通 SUCCESS 链路

### 9. PRD 详情页
- [ ] 前端: 文档列表点击 → 进入详情页 `/prd/:id`
- [ ] 展示 PRD 结构化 JSON（解析 chapters 渲染 Markdown）
- [ ] 详情页内嵌「导出 Word」「重新生成」按钮

### 10. XMind 上传生成
- [x] `POST /api/prd/generate/xmind` multipart 上传
- [x] `XmindParser` 新版 JSON + 旧版 XML 双解析
- [x] 前端 XMind 模式上传区 + 文件选择器联动
- [ ] 用真实 .xmind 文件跑通完整链路（需 AI Key）

### 11. Word 导出
- [x] `GET /api/prd/{id}/export` → .docx 文件流下载
- [x] `WordExporter` 按 PRD 模板格式渲染 10 个章节
- [x] 前端文档列表「导出 Word」按钮
- [ ] 有真实 PRD 数据后验证导出的 docx 排版

### 12. 重新生成
- [x] `POST /api/task/{taskId}/regenerate` 统一接口（4 模块通用）
- [x] `TaskRegenerateService` 读旧 params → 建新 task → 触发 service
- [x] 前端确认框 + 重新生成按钮
- [ ] 配置 AI Key 后验证生成两版对比

---

## 四、体验优化

### 13. 按钮 disabled 状态统一
- [x] PrdGenerateView: `canGenerate` 动态控制
- [x] PrdEnhanceView: `canSubmit` 动态控制
- [x] PrototypeView: `canGenerate` 动态控制
- [x] PrdReviewView: `canReview` 动态控制
- [x] CSS: `.btn-generate:not(:disabled) { blue }` 统一

### 14. 加载状态优化
- [x] 骨架屏（Prototype / Enhance / Review 预览区）
- [ ] SSE 断线自动重连（EventSource onerror 加重试逻辑）

### 15. 全局 401 处理
- [ ] 后端 `AuthenticationEntryPoint`，未认证返回 `401 + Result.fail(401,...)`
- **当前**: Spring Security 默认返回 403

### 16. 移动端适配
- [ ] 左侧 420px 面板在小屏幕下全宽或折叠
- [ ] 顶部 Tab 导航改为下拉菜单

---

## 五、技术债务

### 17. 代码清理
- [ ] 删除未使用的旧实体: `Document.java` / `Task.java`
- [ ] 统一响应体: `ApiResponse` + `Result<T>` 二选一（当前两套并存）
- [ ] 删除 `application-h2.yml`（仅开发时有意义，可保留作为本地测试快捷入口）

### 18. 测试覆盖
- [x] `UserRepositoryTest`（3 个用例）
- [x] QA 自动化测试（37 个 API 用例，100% 通过）
- [ ] 补充 `XmindParser` 单元测试
- [ ] 补充 `PrdGenerateService` 单元测试
- [ ] 补充 `DocumentService.listUnified` 集成测试

### 19. 安全加固
- [ ] JWT secret 环境变量注入（当前默认值写在 yml）
- [ ] 接口限流（Rate Limiting）
- [ ] XMind zip bomb 防护（文件大小上限）

---

## 六、文档

### 20. 文档维护
- [x] `API.md` — 18 个接口完整文档
- [x] `QA_TEST_REPORT.md` — 37 用例测试报告
- [x] `schema-reference.sql` — 5 表 SQL 参考
- [x] `TASKS.md` — 本文件

---

## 进度统计

| 分类 | 已完成 | 未完成 | 暂缓 |
|------|:--:|:--:|:--:|
| 阻塞项 | 1/3 | 1 | 1 |
| 部署与环境 | 0/2 | 0 | 2 |
| 功能模块 | 10/12 | 2 | 0 |
| 体验优化 | 2/5 | 3 | 0 |
| 技术债务 | 2/5 | 3 | 0 |
| 文档 | 4/4 | 0 | 0 |
| **合计** | **19** | **9** | **3** |
