package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.AiRequestContext;
import com.example.aidocumentplatform.ai.prompt.PrototypePromptTemplate;
import com.example.aidocumentplatform.model.dto.request.PrototypeAiEditRequest;
import com.example.aidocumentplatform.model.dto.response.PrototypeAiEditResponse;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import com.example.aidocumentplatform.repository.PrototypeResultRepository;
import com.example.aidocumentplatform.service.PrototypeAiEditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link PrototypeAiEditService} 的实现：同步调用大模型完成一次原型修改。
 *
 * 流程：鉴权 → 取基准 HTML → 组装 prompt → 调 AI → 解析(说明 + 新HTML) → 单页原型落库 → 返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrototypeAiEditServiceImpl implements PrototypeAiEditService {

    private static final String MARK_SUMMARY = "@@SUMMARY@@";
    private static final String MARK_HTML = "@@HTML@@";

    private final PrototypeResultRepository prototypeResultRepository;
    private final PrototypePromptTemplate promptTemplate;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public PrototypeAiEditResponse edit(Long prototypeId, Long userId, PrototypeAiEditRequest request) {
        // 1. 加载 + 鉴权
        PrototypeResult proto = prototypeResultRepository.findById(prototypeId)
                .orElseThrow(() -> new RuntimeException("原型不存在"));
        if (!proto.getUserId().equals(userId)) {
            throw new RuntimeException("无权修改该原型");
        }

        // 2. 确定基准 HTML：优先用前端传来的最新（含手动微调）内容，否则用库中内容
        String baseHtml = (request.getCurrentHtml() != null && !request.getCurrentHtml().isBlank())
                ? request.getCurrentHtml()
                : unwrapJsonString(proto.getContent());
        if (baseHtml == null || baseHtml.isBlank()) {
            throw new IllegalStateException("原型内容为空，无法修改");
        }

        // 3. 组装 prompt 并调用 AI
        String systemPrompt = promptTemplate.getEditSystemPrompt();
        String userPrompt = promptTemplate.buildEditPrompt(
                baseHtml, request.getInstruction(), request.getTargetElement());
        log.info("原型 AI 修改: id={}, instructionLen={}", prototypeId, request.getInstruction().length());
        String aiOutput;
        AiRequestContext.setUserId(userId);
        try {
            aiOutput = aiClient.generate(systemPrompt, userPrompt);
        } finally {
            AiRequestContext.clear();
        }

        // 4. 解析「说明 + 新 HTML」
        ParsedEdit parsed = parseOutput(aiOutput);
        String newHtml = cleanHtml(parsed.html);
        if (newHtml.isBlank()) {
            throw new RuntimeException("AI 未返回有效的 HTML，请调整描述后重试");
        }

        // 5. 落库（多页面原型的内容是 JSON 数组，单条 HTML 不能直接覆盖，交由前端另行保存）
        if (proto.getPrototypeType() == PrototypeType.SINGLE_PAGE) {
            proto.setContent(newHtml);
            prototypeResultRepository.save(proto);
        }

        return PrototypeAiEditResponse.builder()
                .prototypeId(prototypeId)
                .newHtml(newHtml)
                .changeSummary(parsed.summary)
                .build();
    }

    // ==================== 输出解析 ====================

    private record ParsedEdit(String summary, String html) {}

    private ParsedEdit parseOutput(String out) {
        if (out == null) return new ParsedEdit("AI 已完成修改", "");
        int hIdx = out.indexOf(MARK_HTML);
        if (hIdx >= 0) {
            String head = out.substring(0, hIdx).replace(MARK_SUMMARY, "").trim();
            String html = out.substring(hIdx + MARK_HTML.length());
            String summary = head.isBlank() ? "AI 已根据您的描述完成修改" : head;
            return new ParsedEdit(summary, html);
        }
        // 未按约定输出分隔符：整段当作 HTML，给一个兜底说明
        return new ParsedEdit("AI 已根据您的描述完成修改", out);
    }

    /** 去除 markdown 代码块标记，并裁剪到首尾尖括号之间，得到纯 HTML */
    private String cleanHtml(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("```html")) s = s.substring(7);
        else if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        s = s.trim();
        int lt = s.indexOf('<'), end = s.lastIndexOf('>');
        if (lt > 0) s = s.substring(lt);
        if (end >= 0 && end < s.length() - 1) s = s.substring(0, end + 1);
        return s.trim();
    }

    /** 递归解开可能多层 JSON 编码的字符串，直到得到纯 HTML/文本 */
    private String unwrapJsonString(String value) {
        if (value == null) return "";
        String s = value.trim();
        for (int i = 0; i < 5; i++) {
            if (s.startsWith("\"") && s.endsWith("\"")) {
                try {
                    s = objectMapper.readValue(s, String.class);
                    if (s == null) break;
                } catch (Exception e) { break; }
            } else {
                try {
                    JsonNode node = objectMapper.readTree(s);
                    if (node.isTextual()) { s = node.asText(); continue; }
                } catch (Exception ignored) { /* 非 JSON，视为纯 HTML */ }
                break;
            }
        }
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"");
    }
}
