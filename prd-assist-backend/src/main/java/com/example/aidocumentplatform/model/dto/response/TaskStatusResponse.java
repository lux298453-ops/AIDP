package com.example.aidocumentplatform.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class TaskStatusResponse {

    private Long taskId;
    private String taskType;
    private String status;
    private int progress;
    private String outputData;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
