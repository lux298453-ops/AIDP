package com.example.aidocumentplatform.model.dto.request;

import com.example.aidocumentplatform.model.enums.TaskType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TaskCreateRequest {

    @NotNull(message = "任务类型不能为空")
    private TaskType taskType;

    private String inputData;
}
