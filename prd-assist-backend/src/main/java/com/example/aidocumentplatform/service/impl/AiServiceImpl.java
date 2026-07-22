package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.ai.AiClient;
import com.example.aidocumentplatform.ai.PromptTemplateManager;
import com.example.aidocumentplatform.service.AiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private final AiClient aiClient;
    private final PromptTemplateManager promptTemplateManager;

    @Override
    public String generatePrd(String inputData) {
        String systemPrompt = promptTemplateManager.getSystemPrompt();
        String userPrompt = promptTemplateManager.getGeneratePrdUserPrompt(inputData);
        return aiClient.generate(systemPrompt, userPrompt);
    }

    @Override
    public String enhancePrd(String prdContent, String instruction) {
        String systemPrompt = promptTemplateManager.getSystemPrompt();
        String userPrompt = promptTemplateManager.getEnhancePrdUserPrompt(prdContent, instruction);
        return aiClient.generate(systemPrompt, userPrompt);
    }

    @Override
    public String reviewPrd(String prdContent) {
        String systemPrompt = promptTemplateManager.getSystemPrompt();
        String userPrompt = promptTemplateManager.getReviewPrdUserPrompt(prdContent);
        return aiClient.generate(systemPrompt, userPrompt);
    }

    @Override
    public String generatePrototype(String prdContent) {
        String systemPrompt = promptTemplateManager.getSystemPrompt();
        String userPrompt = promptTemplateManager.getGeneratePrototypeUserPrompt(prdContent);
        return aiClient.generate(systemPrompt, userPrompt);
    }
}
