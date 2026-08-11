package com.example.aidocumentplatform.repository;

import com.example.aidocumentplatform.model.entity.AiModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiModelConfigRepository extends JpaRepository<AiModelConfig, Long> {

    Optional<AiModelConfig> findByUserId(Long userId);

    /** 平台默认 AI 配置（最早创建且启用的配置，作为新用户注册时的模板） */
    Optional<AiModelConfig> findFirstByEnabledTrueOrderByIdAsc();
}
