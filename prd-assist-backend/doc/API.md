# API 接口文档 (OpenAPI / Swagger 风格)

> **Base URL**: `http://localhost:8080`  
> **认证方式**: Bearer JWT (`Authorization: Bearer <token>`)  
> **最后更新**: 2026-07-22

---

## 约定

### 统一响应格式 `Result<T>`

```json
{
  "code": 200,
  "message": "success",
  "data": { }
}
```

| code | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 参数校验失败 |
| 401 | 未认证（Token 缺失/无效/过期） |
| 403 | 无权访问 |
| 500 | 服务器内部错误 |
| 4001 | 用户名已存在 |
| 4002 | 用户名或密码错误 |
| 4101 | 文档不存在 |
| 4201 | 任务不存在 |

### 认证说明

除白名单接口外，所有请求必须携带：

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

白名单：`/api/auth/**`、`/api/health`

### 枚举速查

| 枚举类 | 值 |
|--------|-----|
| `TaskType` | `PRD_GENERATE` \| `PRD_ENHANCE` \| `PROTOTYPE` \| `PRD_REVIEW` |
| `TaskStatus` | `PENDING` \| `RUNNING` \| `SUCCESS` \| `FAILED` |
| `DetailLevel` | `CONCISE` \| `DETAILED` |
| `TemplateType` | `STANDARD` |
| `PrototypeType` | `SINGLE_PAGE` \| `MULTI_PAGE` |
| `Platform` | `APP` \| `WEB` \| `MINI_PROGRAM` |
| `DocumentSourceType` | `MANUAL` \| `XMIND` |
| `docType` (视图) | `PRD` \| `PROTOTYPE` \| `REVIEW` |

---

## 1. 认证模块 (Auth)

### 1.1 用户注册

```
POST /api/auth/register
```

**Request Body** (`application/json`):

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| `username` | string | ✅ | 3-32 位，全局唯一 |
| `password` | string | ✅ | 6-64 位，入库前 BCrypt 加密 |
| `nickname` | string | ✅ | 1-50 位，显示名称 |

```json
{
  "username": "zhangsan",
  "password": "123456",
  "nickname": "张三"
}
```

**Response** `200`:

```json
{ "code": 200, "message": "success" }
```

**Error** `400`:

```json
{ "code": 4001, "message": "用户名已存在" }
```

---

### 1.2 用户登录

```
POST /api/auth/login
```

**Request Body** (`application/json`):

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| `username` | string | ✅ | 登录用户名 |
| `password` | string | ✅ | 登录密码 |

```json
{
  "username": "zhangsan",
  "password": "123456"
}
```

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "userId": 1,
    "username": "zhangsan",
    "nickname": "张三"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `token` | string | JWT，后续请求携带 |
| `userId` | number | 用户 ID |
| `username` | string | 登录用户名 |
| `nickname` | string | 显示昵称 |

**Error** `400`:

```json
{ "code": 4002, "message": "用户名或密码错误" }
```

> **注意**: 用户名不存在和密码错误返回相同错误码，防止用户枚举攻击。

---

### 1.3 健康检查

```
GET /api/health
```

🔓 无需认证。

**Response** `200`:

```json
{ "code": 200, "message": "success", "data": "OK" }
```

---

## 2. 工作台 / 首页

> 工作台为纯前端页面，无独立后端 API。数据来源于「我的文档」和「异步任务」模块。

---

## 3. PRD 生成模块

### 3.1 文本输入生成 PRD

```
POST /api/prd/generate
```

🔒 需要认证。

**Request Body** (`application/json`):

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:--:|--------|------|
| `featureName` | string | ✅ | — | 功能名称，≤50 字 |
| `description` | string | 否 | — | 需求描述，≤500 字 |
| `template` | string | 否 | `STANDARD` | 模板类型 |
| `detailLevel` | string | 否 | `DETAILED` | `CONCISE` / `DETAILED` |

```json
{
  "featureName": "用户登录模块",
  "description": "用户通过邮箱和密码完成登录，支持记住密码",
  "template": "STANDARD",
  "detailLevel": "DETAILED"
}
```

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": { "taskId": 1 }
}
```

> **说明**: 这是一个**异步接口**。提交后立即返回 `taskId`，后台生成 PRD。前端通过 [6.1 查询任务状态](#61-查询任务状态) 或 [6.2 SSE 实时进度](#62-sse-实时进度推送) 获取进度和结果。

---

### 3.2 XMind 文件上传生成 PRD

```
POST /api/prd/generate/xmind
Content-Type: multipart/form-data
```

🔒 需要认证。

**Form Data**:

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:--:|--------|------|
| `file` | file | ✅ | — | `.xmind` 文件 |
| `template` | string | 否 | `STANDARD` | 模板类型 |
| `detailLevel` | string | 否 | `DETAILED` | `CONCISE` / `DETAILED` |

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "taskId": 1,
    "fileName": "需求大纲.xmind",
    "filePath": "uploads/2026-07/99ef2b17_需求大纲.xmind",
    "outline": "- 用户登录\n  - 邮箱登录\n  - 手机号登录"
  }
}
```

| 字段 | 说明 |
|------|------|
| `taskId` | 异步任务 ID |
| `fileName` | 原始文件名 |
| `filePath` | 服务端存储路径 |
| `outline` | XMind 解析后的结构化大纲文本 |

> **说明**: 后端自动解压 `.xmind`（zip 格式），解析 `content.json`（新版）或 `content.xml`（旧版），提取大纲文本后作为 `description` 传给 AI 生成 PRD。

---

### 3.3 导出 PRD 为 Word

```
GET /api/prd/{id}/export
```

🔒 需要认证。

**Path Parameters**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `id` | number | `prd_document.id` |

**Response** `200`:

```
Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document
Content-Disposition: attachment; filename=用户登录模块.docx

<.docx 文件流>
```

> **说明**: 将 `prd_document.content`（结构化 JSON）按标准 PRD 模板格式渲染为 Word 文档，包含修订记录、需求概述、功能列表、功能需求描述等 10 个章节。

---

## 4. PRD 增强模块

### 4.1 文本输入增强

```
POST /api/prd/enhance
```

🔒 需要认证。

**Request Body** (`application/json`):

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| `prdContent` | string | 否* | 原始 PRD 文本，≤5000 字 |
| `prdDocumentId` | number | 否* | 引用已有 PRD ID |
| `contentTypes` | string[] | 否 | 补充类型：`structure` / `flow` / `data` / `testcase` |
| `instruction` | string | 否 | 自定义增强指令，≤500 字 |

> `prdContent` 和 `prdDocumentId` 至少提供一个。

```json
{
  "prdContent": "用户登录模块：邮箱+密码登录...",
  "contentTypes": ["structure", "flow", "data"],
  "instruction": "请重点补充移动端适配方案"
}
```

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": { "taskId": 1 }
}
```

**contentTypes 说明**:

| 值 | 中文名 | AI 生成的补充内容 |
|------|--------|-------------------|
| `structure` | 页面结构图 | 页面层级、导航关系、路由设计 |
| `flow` | 流程图 | 核心业务流程（支持 Mermaid 语法） |
| `data` | 数据字段 | 字段定义、类型、校验规则、默认值 |
| `testcase` | 测试用例 | 输入、预期输出、边界条件 |

> **说明**: 异步接口。增强结果存入 `prd_document` 表，通过 taskId 获取进度。

---

### 4.2 Word 素材上传增强

```
POST /api/prd/enhance/word
Content-Type: multipart/form-data
```

🔒 需要认证。

**Form Data**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| `file` | file | ✅ | `.doc` / `.docx` 参考素材 |
| `prdContent` | string | 否 | 原始 PRD 文本 |
| `prdDocumentId` | number | 否 | 引用已有 PRD ID |
| `contentTypes` | string | 否 | 逗号分隔，如 `structure,flow,data` |
| `instruction` | string | 否 | 自定义增强指令 |

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": { "taskId": 1 }
}
```

> **说明**: 用 Apache POI 提取 Word 文件纯文本内容，拼入 prompt 作为参考素材。

---

## 5. 原型图生成模块

### 5.1 提交原型生成

```
POST /api/prototype/generate
```

🔒 需要认证。

**Request Body** (`application/json`):

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:--:|--------|------|
| `description` | string | ✅ | — | 功能描述，≤2000 字 |
| `prototypeType` | string | 否 | `SINGLE_PAGE` | `SINGLE_PAGE` / `MULTI_PAGE` |
| `platform` | string | 否 | `APP` | `APP` / `WEB` / `MINI_PROGRAM` |
| `prdDocumentId` | number | 否 | — | 关联的 PRD 文档 ID |

```json
{
  "description": "电商App：登录页、商品列表、商品详情、购物车、个人中心",
  "prototypeType": "MULTI_PAGE",
  "platform": "APP"
}
```

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": { "taskId": 1 }
}
```

> **说明**: 异步接口。
> - `SINGLE_PAGE`: AI 返回单个 HTML 文件
> - `MULTI_PAGE`: AI 返回 JSON 数组 `[{title, order, html}, ...]`，每页一个完整 HTML
> - 生成结果存入 `prototype_result.content`

---

### 5.2 获取原型 HTML

```
GET /api/prototype/{id}
```

🔒 需要认证。

**Path Parameters**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `id` | number | `prototype_result.id` |

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "userId": 1,
    "taskId": 1,
    "prototypeType": "SINGLE_PAGE",
    "platform": "APP",
    "content": "<!DOCTYPE html><html>...</html>",
    "createdAt": "2026-07-22T10:30:00"
  }
}
```

> **前端用法**: 单页面直接 `<iframe srcdoc="content">`；多页面 `JSON.parse(content)` 后渲染缩略图导航 + 切换 iframe。使用 `sandbox="allow-scripts allow-same-origin"` 隔离。

---

## 6. PRD 审查模块

### 6.1 提交审查

```
POST /api/prd/review
```

🔒 需要认证。

**Request Body** (`application/json`):

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| `prdContent` | string | 否* | PRD 文本，≤8000 字 |
| `prdDocumentId` | number | 否* | 引用已有 PRD ID |
| `dimensions` | string[] | 否 | 审查维度：`completeness` / `consistency` / `compliance` |
| `requirement` | string | 否 | 自定义审查要求，≤500 字 |

```json
{
  "prdContent": "用户登录模块：邮箱+密码...",
  "dimensions": ["completeness", "compliance"],
  "requirement": "请重点关注支付流程的安全性"
}
```

**审查维度说明**:

| 值 | 中文名 | 审查内容 |
|------|--------|----------|
| `completeness` | 功能完整性 | 功能是否遗漏、边界是否覆盖、异常场景是否考虑 |
| `consistency` | 逻辑一致性 | 模块间逻辑是否矛盾、术语是否统一 |
| `compliance` | 合规性 | 行业规范、安全要求、数据隐私标准 |

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": { "taskId": 1 }
}
```

> **说明**: 异步接口。审查结果存入 `review_report` 表，格式为：
```json
{
  "summary": "总体评价",
  "score": 85,
  "issues": [
    {
      "severity": "CRITICAL",
      "dimension": "compliance",
      "chapterIndex": 2,
      "chapterTitle": "3. 登录与认证",
      "location": "登录模块",
      "targetText": "当前 PRD 中与该问题直接相关的原文片段，用于前端精准定位和高亮",
      "description": "未提及密码加密存储方案",
      "suggestion": "增加BCrypt加密存储要求"
    }
  ]
}
```

**issues 字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `severity` | string | 严重程度：`CRITICAL` / `MAJOR` / `MINOR` / `SUGGESTION` |
| `dimension` | string | 审查维度：`completeness` / `consistency` / `compliance` |
| `chapterIndex` | number | 问题所属章节下标，0-based；无法定位时为 `-1` |
| `chapterTitle` | string | 与 `chapterIndex` 对应的章节标题 |
| `location` | string | 更细的问题位置描述，如段落、模块、小节 |
| `targetText` | string | 从 PRD 原文逐字摘录的问题相关片段，20~200 字；整体缺失类问题可为空字符串 |
| `description` | string | 问题描述 |
| `suggestion` | string | 修改建议 |

**严重程度**: `CRITICAL` > `MAJOR` > `MINOR` > `SUGGESTION`

---

### 6.2 获取审查报告

```
GET /api/review/{id}
```

🔒 需要认证。

**Path Parameters**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `id` | number | `review_report.id` |

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "userId": 1,
    "prdDocumentId": 1,
    "taskId": 1,
    "dimensions": "[\"completeness\",\"compliance\"]",
    "issues": "{ \"summary\": \"...\", \"score\": 85, \"issues\": [...] }",
    "createdAt": "2026-07-22T10:30:00"
  }
}
```

---

### 6.3 AI 修复严重项 / 生成修订版 PRD

```
POST /api/review/{id}/fix
```

🔒 需要认证。

**说明**: 异步接口。用于批量修复严重/重要问题，或作为内联修复失败后的兜底流程。接口会创建新的 PRD 文档版本，不覆盖原 PRD。

**Path Parameters**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `id` | number | `review_report.id` |

**Request Body**:

```json
{
  "issueIndexes": [0, 2],
  "severities": ["CRITICAL", "MAJOR"],
  "sourcePrdDocumentId": 12
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| `issueIndexes` | number[] | 否 | 指定修复的问题下标，0-based，对应报告 `issues` 数组 |
| `severities` | string[] | 否 | 按严重程度批量修复；不传时默认修复 `CRITICAL` + `MAJOR` |
| `sourcePrdDocumentId` | number | 否 | 当前 PRD 文档 ID；从 PRD 编辑页发起时用于基于当前版本修复 |

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": { "taskId": 101 }
}
```

> 前端通过任务 SSE 获取进度；成功后 `task.resultRefId` 为新生成的 `prd_document.id`。

---

### 6.4 内联精准修复单条问题

```
POST /api/review/{id}/fix-inline
```

🔒 需要认证。

**说明**: 同步接口。用于单条问题的快速修复。AI 只返回 `oldText -> newText` 文本替换，后端在目标章节内定位并替换，保存为新版本；前端用 `patchedContent` 原地更新当前章节并高亮修改内容。

**Path Parameters**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `id` | number | `review_report.id` |

**Request Body**:

```json
{
  "issueIndex": 0,
  "sourcePrdDocumentId": 12
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| `issueIndex` | number | 是 | 要修复的问题下标，0-based，对应报告 `issues` 数组 |
| `sourcePrdDocumentId` | number | 否 | 当前 PRD 文档 ID；不传时使用审查报告关联的 PRD |

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "prdDocumentId": 13,
    "chapterIndex": 2,
    "oldText": "原章节中的待修复文本片段",
    "newText": "修复后的文本片段",
    "patchedContent": "替换后的章节完整内容",
    "changeSummary": "补充了密码加密存储要求"
  }
}
```

**前端交互约定**:

- 单条问题只展示「内联修复此条」。
- 内联修复成功后，当前 PRD 页面原地更新章节内容、高亮修改片段，并从问题列表移除该问题。
- 内联修复失败时，前端提示是否改为生成修订版 PRD；用户确认后再调用 `POST /api/review/{id}/fix`。
- 「AI 修复此条」不再作为常驻按钮展示，仅作为内联失败后的隐藏兜底能力。

---

## 7. 我的文档 / 通用文档模块

### 7.1 聚合文档列表

```
GET /api/documents
```

🔒 需要认证。

**Query Parameters**:

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|:--:|--------|------|
| `keyword` | string | 否 | — | 标题模糊搜索 |
| `taskType` | string | 否 | — | 按类型筛选：`PRD_GENERATE` / `PROTOTYPE` / `PRD_REVIEW` |
| `page` | int | 否 | `0` | 页码（0 开始） |
| `size` | int | 否 | `20` | 每页条数 |

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "content": [
      {
        "id": 1,
        "docType": "PRD",
        "title": "用户登录模块",
        "description": "用户通过邮箱和密码完成登录",
        "taskType": "PRD_GENERATE",
        "taskId": 1,
        "prdDocumentId": null,
        "createdAt": "2026-07-22T10:30:00"
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "number": 0,
    "size": 20
  }
}
```

**docType 来源**:

| docType | 来源表 |
|---------|--------|
| `PRD` | `prd_document` |
| `PROTOTYPE` | `prototype_result` |
| `REVIEW` | `review_report` |

**示例**:

```
# 只看 PRD 文档
GET /api/documents?taskType=PRD_GENERATE

# 搜索标题含"登录"的文档
GET /api/documents?keyword=登录

# 第二页
GET /api/documents?page=1&size=10
```

---

### 7.2 文件上传

```
POST /api/documents/upload
Content-Type: multipart/form-data
```

🔒 需要认证。

**Form Data**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| `file` | file | ✅ | 支持 XMind / Word / PDF / TXT / MD |

**Response** `200`: 返回 `Document` 实体。

> ⚠️ 当前为简化实现，完整上传逻辑待补充。

---

## 8. 异步任务模块

### 8.1 查询任务状态

```
GET /api/task/{taskId}
```

🔒 需要认证。

**Path Parameters**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `taskId` | number | `async_task.id` |

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "userId": 1,
    "taskType": "PRD_GENERATE",
    "status": "SUCCESS",
    "inputParams": "{\"featureName\":\"用户登录\"}",
    "resultRefId": 1,
    "errorMessage": null,
    "createdAt": "2026-07-22T10:30:00",
    "updatedAt": "2026-07-22T10:32:00"
  }
}
```

**status 枚举**:

| 值 | 含义 | resultRefId | 对应查询 |
|------|------|:--:|------|
| `PENDING` | 等待执行 | null | — |
| `RUNNING` | 正在执行 | null | — |
| `SUCCESS` | 执行成功 | 结果表 ID | 见下方映射表 |
| `FAILED` | 执行失败 | null | 查看 errorMessage |

**taskType → 结果表映射**:

| taskType | resultRefId 指向 |
|----------|-----------------|
| `PRD_GENERATE` | `prd_document.id` → `GET /api/prd/{id}/export` |
| `PRD_ENHANCE` | `prd_document.id` |
| `PROTOTYPE` | `prototype_result.id` → `GET /api/prototype/{id}` |
| `PRD_REVIEW` | `review_report.id` → `GET /api/review/{id}` |

---

### 8.2 SSE 实时进度推送

```
GET /api/task/{taskId}/stream
Content-Type: text/event-stream
```

🔒 需要认证。

**SSE 事件格式**:

```
event: progress
data: {"taskId":1,"progress":70,"message":"AI 生成完成，正在保存..."}
```

**典型进度节点**:

| progress | message |
|----------|---------|
| 10% | AI 正在生成 PRD... |
| 15% | AI 正在生成原型 HTML... |
| 60% | 代码生成完成，正在清洗... |
| 70% | AI 生成完成，正在保存... |
| 85% | 正在保存原型... |
| 100% | PRD 生成完成 |
| 0% (失败) | 生成失败: {详情} |

**前端示例**:

```javascript
const es = new EventSource(`/api/task/${taskId}/stream`)
es.addEventListener('progress', (e) => {
  const { progress, message } = JSON.parse(e.data)
  console.log(progress, message)
  if (progress >= 100) es.close()
})
es.onerror = () => es.close()
```

> **超时**: SSE 连接最长 30 分钟，超时自动关闭。

---

### 8.3 重新生成

```
POST /api/task/{taskId}/regenerate
```

🔒 需要认证。

**Path Parameters**:

| 参数 | 类型 | 说明 |
|------|------|------|
| `taskId` | number | 原任务 ID |

**Response** `200`:

```json
{
  "code": 200,
  "message": "success",
  "data": { "taskId": 2 }
}
```

> **说明**: 读取原任务的 `inputParams`，创建新的 `async_task` 记录并触发同样的异步生成流程。**旧任务和旧结果原封不动**，方便版本对比。支持所有 `taskType`（PRD_GENERATE / PRD_ENHANCE / PROTOTYPE / PRD_REVIEW）。

---

### 8.4 任务列表

```
GET /api/tasks
```

🔒 需要认证。

**Response** `200`: 返回当前用户的所有 `async_task` 列表（按创建时间倒序）。

---

### 8.5 创建通用任务

```
POST /api/tasks
```

🔒 需要认证。

**Request Body** (`application/json`):

| 字段 | 类型 | 必填 | 说明 |
|------|------|:--:|------|
| `taskType` | string | ✅ | TaskType 枚举值 |
| `inputData` | string | 否 | 任务输入参数 JSON |

> ⚠️ 建议优先使用各模块专用接口（如 `/api/prd/generate`），此接口为通用兼容接口。

---

### 8.6 任务进度 SSE（兼容路径）

```
GET /api/tasks/{id}/progress
Content-Type: text/event-stream
```

> 与 8.2 功能相同，路径风格为 `/api/tasks/{id}/progress`。

---

## 附录 A: AI 测试接口

```
POST /api/ai/test
```

🔒 需要认证。

**Request Body**:

```json
{
  "systemPrompt": "你是一个助手",
  "userPrompt": "用一句话介绍你自己"
}
```

**Response** `200`: 返回 Claude 原始 JSON 响应。

> ⚠️ 临时调试接口，验证 AI Key 配置是否生效。生产环境可删除。

---

## 附录 B: 异步任务完整生命周期

```
POST /api/prd/generate
  │
  ├─ 立即返回 { taskId: 1, status: PENDING }
  │
  ├─ [后台 @Async 线程]
  │   ├─ status → RUNNING
  │   ├─ SSE push: 10% → 70% → 100%
  │   │
  │   ├─ SUCCESS:
  │   │   ├─ 结果写入对应结果表 (prd_document / prototype_result / review_report)
  │   │   ├─ async_task.resultRefId = 结果表.id
  │   │   └─ async_task.status = SUCCESS
  │   │
  │   └─ FAILED:
  │       ├─ async_task.errorMessage = 错误详情
  │       └─ async_task.status = FAILED
  │
  └─ 前端通过轮询 GET /api/task/{taskId} 或 SSE /api/task/{taskId}/stream 获取结果
```

## 附录 C: 错误码全表

| code | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 参数校验失败 |
| 401 | 未认证 |
| 403 | 无权访问 |
| 500 | 服务器内部错误 |
| 4001 | 用户名已存在 |
| 4002 | 用户名或密码错误 |
| 4101 | 文档不存在 |
| 4102 | 文件上传失败 |
| 4201 | 任务不存在 |
