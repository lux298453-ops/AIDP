package com.example.aidocumentplatform;

import com.example.aidocumentplatform.ai.prompt.PrototypePromptTemplate;
import com.example.aidocumentplatform.model.enums.Platform;
import com.example.aidocumentplatform.model.enums.PrototypeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

public class PrototypePromptTemplateTest {

    @Test
    void testBuildUserPromptDoesNotThrow() {
        PrototypePromptTemplate template = new PrototypePromptTemplate();
        for (Platform platform : Platform.values()) {
            for (PrototypeType type : PrototypeType.values()) {
                assertThatCode(() -> {
                    template.buildUserPrompt("抽奖页面", platform, type, false, "简要描述");
                    template.buildUserPrompt("抽奖页面", platform, type, true, "简要描述");
                }).doesNotThrowAnyException();
            }
        }
        assertThatCode(() -> {
            template.buildEditPrompt("<html><body>123</body></html>", "改一下标题", null);
            template.buildEditPatchPrompt("<html><body>123</body></html>", "改一下标题", "h1");
        }).doesNotThrowAnyException();
    }
}
