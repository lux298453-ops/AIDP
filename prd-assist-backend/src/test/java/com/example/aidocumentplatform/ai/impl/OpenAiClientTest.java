package com.example.aidocumentplatform.ai.impl;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiClientTest {

    private final OpenAiClient client = new OpenAiClient(
            WebClient.builder().build(),
            "https://api.openai.test/v1/responses",
            "test-key",
            "gpt-test",
            1024,
            "high",
            "responses",
            "chat-completions",
            true,
            false,
            "bearer",
            "local-image-extension",
            "xhigh",
            true);

    @Test
    void extractsTopLevelOutputText() {
        String response = """
                {
                  "id": "resp_123",
                  "output_text": "{\\"title\\":\\"PRD\\"}"
                }
                """;

        assertEquals("{\"title\":\"PRD\"}", client.extractContent(response));
    }

    @Test
    void extractsNestedResponseOutputTextBlocks() {
        String response = """
                {
                  "id": "resp_123",
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        { "type": "output_text", "text": "<html>" },
                        { "type": "output_text", "text": "<body>OK</body></html>" }
                      ]
                    }
                  ]
                }
                """;

        assertEquals("<html>\n<body>OK</body></html>", client.extractContent(response));
    }

    @Test
    void extractsChatCompletionChoiceContent() {
        String response = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "<html>relay ok</html>"
                      }
                    }
                  ]
                }
                """;

        assertEquals("<html>relay ok</html>", client.extractContent(response));
    }

    @Test
    void rejectsNonCompletionJsonResponse() {
        String response = """
                {
                  "registration_enabled": true,
                  "site_name": "Flintic API",
                  "site_logo": "data:image/jpeg;base64,..."
                }
                """;

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.extractContent(response));
        assertEquals("OpenAI API 返回格式不是模型生成结果，请检查 api-url/api-type/append-api-path 配置", ex.getMessage());
    }

    @Test
    void throwsOpenAiErrorMessage() {
        String response = """
                {
                  "error": {
                    "message": "Invalid model"
                  }
                }
                """;

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.extractContent(response));
        assertEquals("Invalid model", ex.getMessage());
    }

    @Test
    void extractsHttpErrorBodyMessage() {
        String response = """
                {
                  "error": {
                    "message": "Incorrect API key provided"
                  }
                }
                """;

        assertEquals("Incorrect API key provided", client.extractErrorMessage(response));
    }

    @Test
    void keepsRelayRootUrlWhenAppendApiPathDisabled() {
        assertEquals(
                "https://api.flintic.uk",
                OpenAiClient.normalizeApiUrl("https://api.flintic.uk", "chat-completions", false));
    }

    @Test
    void appendsChatPathForRootUrlWhenEnabled() {
        assertEquals(
                "https://api.flintic.uk/v1/chat/completions",
                OpenAiClient.normalizeApiUrl("https://api.flintic.uk", "chat-completions", true));
    }

    @Test
    void appendsResponsesPathForRootUrlWhenEnabled() {
        assertEquals(
                "https://api.flintic.uk/v1/responses",
                OpenAiClient.normalizeApiUrl("https://api.flintic.uk", "responses", true));
    }
}
