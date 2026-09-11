package com.example.aidocumentplatform.model.dto.request;

import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PrototypeClarifyRequest {

    @NotBlank(message = "功能描述不能为空")
    @Size(max = 50000, message = "功能描述最大 50000 字")
    private String description;

    private Platform platform = Platform.APP;

    private PrototypeType prototypeType = PrototypeType.SINGLE_PAGE;

    private boolean hasReferenceImage;
}
