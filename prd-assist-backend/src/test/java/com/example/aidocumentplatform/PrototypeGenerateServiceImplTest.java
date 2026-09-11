package com.example.aidocumentplatform;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.prompt.PrototypePromptTemplate;
import com.example.aidocumentplatform.common.FileStorage;
import com.example.aidocumentplatform.model.dto.PrototypeAssetPlan;
import com.example.aidocumentplatform.model.dto.request.PrototypeGenerateRequest;
import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrototypeResultRepository;
import com.example.aidocumentplatform.service.PrototypeImageService;
import com.example.aidocumentplatform.service.TaskService;
import com.example.aidocumentplatform.service.impl.AsyncTaskLifecycleService;
import com.example.aidocumentplatform.service.impl.IdempotentTaskService;
import com.example.aidocumentplatform.service.impl.PrototypeGenerateServiceImpl;
import com.example.aidocumentplatform.service.impl.PrototypeQualityGuard;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrototypeGenerateServiceImplTest {

    @Test
    void shouldGenerateAndBindThreeIndependentPageAssets() {
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        PrototypeResultRepository resultRepository = mock(PrototypeResultRepository.class);
        AiClient aiClient = mock(AiClient.class);
        TaskService taskService = mock(TaskService.class);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        IdempotentTaskService idempotentTaskService = mock(IdempotentTaskService.class);
        AsyncTaskLifecycleService lifecycleService = mock(AsyncTaskLifecycleService.class);
        PrototypeImageService imageService = mock(PrototypeImageService.class);
        FileStorage fileStorage = mock(FileStorage.class);

        when(taskRepository.findById(91L)).thenReturn(Optional.of(AsyncTask.builder().id(91L).build()));
        when(aiClient.generateStream(anyString(), anyString(), any())).thenReturn("""
                [
                  {"title":"锁屏页","order":1,"html":"<html><head></head><body><img src=\\\"__PROTOTYPE_ASSET_lock_screen__\\\"></body></html>"},
                  {"title":"主屏页","order":2,"html":"<html><head></head><body><img src=\\\"__PROTOTYPE_ASSET_home_screen__\\\"></body></html>"},
                  {"title":"组件页","order":3,"html":"<html><head></head><body><img src=\\\"__PROTOTYPE_ASSET_widget_screen__\\\"></body></html>"}
                ]
                """);
        when(imageService.generate(any(PrototypeAssetPlan.class), anyLong())).thenAnswer(invocation -> {
            PrototypeAssetPlan plan = invocation.getArgument(0);
            return new PrototypeImageService.GeneratedImage(
                    plan.getKey().getBytes(StandardCharsets.UTF_8), "image/png", ".png");
        });
        when(fileStorage.store(any(byte[].class), anyString())).thenAnswer(invocation ->
                "/uploads/" + invocation.getArgument(1, String.class));
        when(lifecycleService.savePrototypeResultAndMarkSuccess(anyLong(), any(PrototypeResult.class)))
                .thenAnswer(invocation -> {
                    PrototypeResult result = invocation.getArgument(1);
                    result.setId(501L);
                    return result;
                });

        PrototypeGenerateServiceImpl service = new PrototypeGenerateServiceImpl(
                taskRepository,
                resultRepository,
                aiClient,
                new PrototypePromptTemplate(),
                taskService,
                applicationContext,
                idempotentTaskService,
                lifecycleService,
                imageService,
                fileStorage,
                new PrototypeQualityGuard());

        PrototypeGenerateRequest request = new PrototypeGenerateRequest();
        request.setDescription("生成小猫主题的锁屏页、主屏页和组件页");
        request.setGenerationBrief("三个页面分别使用独立小猫主题图片");
        request.setPlatform(Platform.APP);
        request.setPrototypeType(PrototypeType.MULTI_PAGE);
        request.setAssetPlans(List.of(
                plan("lock_screen", "锁屏页"),
                plan("home_screen", "主屏页"),
                plan("widget_screen", "组件页")));

        service.execute(91L, request, 7L);

        verify(imageService, times(3)).generate(any(PrototypeAssetPlan.class), anyLong());
        verify(fileStorage, times(3)).store(any(byte[].class), anyString());
        ArgumentCaptor<PrototypeResult> resultCaptor = ArgumentCaptor.forClass(PrototypeResult.class);
        verify(lifecycleService).savePrototypeResultAndMarkSuccess(anyLong(), resultCaptor.capture());

        PrototypeResult result = resultCaptor.getValue();
        assertThat(result.getContent())
                .contains("data:image/png;base64,bG9ja19zY3JlZW4=")
                .contains("data:image/png;base64,aG9tZV9zY3JlZW4=")
                .contains("data:image/png;base64,d2lkZ2V0X3NjcmVlbg==")
                .doesNotContain("__PROTOTYPE_ASSET_");
        assertThat(result.getReferenceImageUrl()).contains("prototype-lock_screen.png");
    }

    private PrototypeAssetPlan plan(String key, String targetPage) {
        return PrototypeAssetPlan.builder()
                .key(key)
                .targetPage(targetPage)
                .required(true)
                .source("GENERATED")
                .role(targetPage + "主题图")
                .prompt(targetPage + "独立小猫主题图片")
                .aspectRatio("9:16")
                .transparentBackground(false)
                .build();
    }
}
