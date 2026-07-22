package com.example.aidocumentplatform.service;

public interface AiService {

    String generatePrd(String inputData);

    String enhancePrd(String prdContent, String instruction);

    String reviewPrd(String prdContent);

    String generatePrototype(String prdContent);
}
