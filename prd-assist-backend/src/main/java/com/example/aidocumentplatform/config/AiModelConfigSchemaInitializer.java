package com.example.aidocumentplatform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelConfigSchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureAiModelConfigSchema();
        } catch (Exception e) {
            log.warn("Failed to initialize ai_model_config schema, please check database permissions", e);
        }
    }

    private void ensureAiModelConfigSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_model_config (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    provider VARCHAR(30) NOT NULL DEFAULT 'openai',
                    base_url VARCHAR(500) NOT NULL DEFAULT 'https://api.openai.com',
                    api_key VARCHAR(1000),
                    model VARCHAR(100) NOT NULL DEFAULT 'gpt-5.1',
                    api_type VARCHAR(40) NOT NULL DEFAULT 'responses',
                    append_api_path BOOLEAN NOT NULL DEFAULT TRUE,
                    openai_auth_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    auth_header_type VARCHAR(30) NOT NULL DEFAULT 'bearer',
                    actor_authorization VARCHAR(500),
                    reasoning_effort VARCHAR(20),
                    disable_response_storage BOOLEAN NOT NULL DEFAULT TRUE,
                    max_output_tokens INTEGER NOT NULL DEFAULT 16384,
                    image_detail VARCHAR(20) NOT NULL DEFAULT 'high',
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("ALTER TABLE ai_model_config ADD COLUMN IF NOT EXISTS auth_header_type VARCHAR(30)");
        jdbcTemplate.execute("UPDATE ai_model_config SET auth_header_type = 'bearer' WHERE auth_header_type IS NULL OR auth_header_type = ''");
        jdbcTemplate.execute("ALTER TABLE ai_model_config ALTER COLUMN auth_header_type SET DEFAULT 'bearer'");
        jdbcTemplate.execute("ALTER TABLE ai_model_config ALTER COLUMN auth_header_type SET NOT NULL");
    }
}
