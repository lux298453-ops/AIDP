package com.example.aidocumentplatform.model.dto.request;

import com.example.aidocumentplatform.model.dto.PrototypeAssetPlan;

import com.example.aidocumentplatform.model.enums.AssetMode;
import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 原型生成请求体。
 * 参考图通过 multipart 上传后由 Controller 填入 base64 / 路径字段。
 */
@Data
public class PrototypeGenerateRequest {

    /** 功能描述，必填 */
    @NotBlank(message = "功能描述不能为空")
    @Size(max = 50000, message = "功能描述最多50000字")
    private String description;

    /** 原型类型 */
    private PrototypeType prototypeType = PrototypeType.SINGLE_PAGE;

    /** 终端类型 */
    private Platform platform = Platform.APP;

    /** 素材模式 */
    private AssetMode assetMode = AssetMode.AUTO;

    /** 澄清问答的答案列表，按顺序拼接到需求理解中 */
    private List<String> clarificationAnswers;

    /** 澄清阶段形成的结构化生成简报，由后端 AI 生成，用户无需填写。 */
    @Size(max = 12000, message = "生成简报最多12000字")
    private String generationBrief;

    /** 澄清阶段自动生成的内部素材计划，不在前端作为配置项展示。 */
    private PrototypeAssetPlan assetPlan;

    /** 多页面独立素材计划，兼容旧版 assetPlan。 */
    @Size(max = 3, message = "页面素材计划最多3项")
    private List<PrototypeAssetPlan> assetPlans;

    /** 关联的 PRD 文档 ID（可选） */
    private Long prdDocumentId;

    // ── 风格参考图（可选，由 Controller 从 multipart 填充） ──

    /** 原始文件名 */
    private String referenceImageFileName;

    /** 存储路径（FileStorage 返回） */
    private String referenceImagePath;

    /** MIME，如 image/png */
    private String referenceImageMimeType;

    /**
     * 压缩后的 base64（不含 data: 前缀）。
     * 支持 vision 的模型可直接使用；文本模型仅作风格提示。
     */
    private String referenceImageBase64;

    /** 图片像素宽（若可解析） */
    private Integer referenceImageWidth;

    /** 图片像素高（若可解析） */
    private Integer referenceImageHeight;
}
