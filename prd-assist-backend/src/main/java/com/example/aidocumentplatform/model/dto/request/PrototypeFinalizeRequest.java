package com.example.aidocumentplatform.model.dto.request;

import com.example.aidocumentplatform.model.dto.PrototypeAssetPlan;
import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class PrototypeFinalizeRequest {

    @NotBlank(message = "功能描述不能为空")
    @Size(max = 50000, message = "功能描述最多 50000 字")
    private String description;

    private Platform platform = Platform.APP;

    private PrototypeType prototypeType = PrototypeType.SINGLE_PAGE;

    private boolean hasReferenceImage;

    @Size(max = 12000, message = "初步生成简报最多 12000 字")
    private String generationBrief;

    private PrototypeAssetPlan assetPlan;

    @Size(max = 3, message = "页面素材计划最多3项")
    private List<PrototypeAssetPlan> assetPlans;

    @NotEmpty(message = "确认答案不能为空")
    @Size(max = 3, message = "确认答案最多 3 条")
    private List<@NotBlank @Size(max = 2000) String> clarificationAnswers;
}
