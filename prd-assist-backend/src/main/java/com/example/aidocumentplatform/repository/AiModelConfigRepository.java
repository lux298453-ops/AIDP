package com.example.aidocumentplatform.repository;

import com.example.aidocumentplatform.model.entity.AiModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiModelConfigRepository extends JpaRepository<AiModelConfig, Long> {

    Optional<AiModelConfig> findByUserId(Long userId);
}
