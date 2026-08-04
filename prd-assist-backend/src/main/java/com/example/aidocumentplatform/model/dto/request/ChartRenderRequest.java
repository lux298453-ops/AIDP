package com.example.aidocumentplatform.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChartRenderRequest {

    @NotBlank(message = "图表源码不能为空")
    private String code;

    private String lang;

    private String format;
}
