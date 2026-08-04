package com.example.aidocumentplatform.repository;

import java.time.LocalDateTime;

public interface DocumentListProjection {

    Long getId();

    String getDocType();

    String getTitle();

    String getDescription();

    String getTaskType();

    Long getTaskId();

    Long getPrdDocumentId();

    LocalDateTime getCreatedAt();
}
