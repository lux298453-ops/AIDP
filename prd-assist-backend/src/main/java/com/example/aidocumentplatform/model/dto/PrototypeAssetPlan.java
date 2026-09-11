package com.example.aidocumentplatform.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrototypeAssetPlan {

    /** Stable placeholder key, for example lock_screen or home_screen. */
    private String key;

    /** Human-readable target page title used by the multi-page HTML planner. */
    private String targetPage;

    private boolean required;
    private String source;
    private String role;
    private String prompt;
    private String aspectRatio;
    private boolean transparentBackground;
}
