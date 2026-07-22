package com.example.aidocumentplatform.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PrdGenerateTask {

    private final TaskExecutor taskExecutor;

    public void execute(Long taskId) {
        taskExecutor.executeTask(taskId);
    }
}
