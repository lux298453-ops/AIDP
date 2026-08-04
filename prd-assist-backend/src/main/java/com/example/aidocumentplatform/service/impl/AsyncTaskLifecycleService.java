package com.example.aidocumentplatform.service.impl;

import com.example.aidocumentplatform.model.entity.AsyncTask;
import com.example.aidocumentplatform.model.entity.PrdDocument;
import com.example.aidocumentplatform.model.entity.PrototypeResult;
import com.example.aidocumentplatform.model.entity.ReviewReport;
import com.example.aidocumentplatform.model.enums.TaskStatus;
import com.example.aidocumentplatform.repository.AsyncTaskRepository;
import com.example.aidocumentplatform.repository.PrdDocumentRepository;
import com.example.aidocumentplatform.repository.PrototypeResultRepository;
import com.example.aidocumentplatform.repository.ReviewReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AsyncTaskLifecycleService {

    private final AsyncTaskRepository asyncTaskRepository;
    private final PrdDocumentRepository prdDocumentRepository;
    private final PrototypeResultRepository prototypeResultRepository;
    private final ReviewReportRepository reviewReportRepository;

    @Transactional
    public void markRunning(Long taskId) {
        AsyncTask task = loadTask(taskId);
        task.setStatus(TaskStatus.RUNNING);
        task.setErrorMessage(null);
        asyncTaskRepository.save(task);
    }

    @Transactional
    public void markSuccess(Long taskId, Long resultRefId) {
        AsyncTask task = loadTask(taskId);
        task.setStatus(TaskStatus.SUCCESS);
        task.setErrorMessage(null);
        task.setResultRefId(resultRefId);
        asyncTaskRepository.save(task);
    }

    @Transactional
    public void markFailed(Long taskId, String errorMessage) {
        AsyncTask task = loadTask(taskId);
        task.setStatus(TaskStatus.FAILED);
        task.setErrorMessage(errorMessage);
        asyncTaskRepository.save(task);
    }

    @Transactional
    public PrdDocument savePrdDocumentAndMarkSuccess(Long taskId, PrdDocument document) {
        PrdDocument saved = prdDocumentRepository.save(document);
        markSuccess(taskId, saved.getId());
        return saved;
    }

    @Transactional
    public PrototypeResult savePrototypeResultAndMarkSuccess(Long taskId, PrototypeResult prototypeResult) {
        PrototypeResult saved = prototypeResultRepository.save(prototypeResult);
        markSuccess(taskId, saved.getId());
        return saved;
    }

    @Transactional
    public ReviewReport saveReviewReportAndMarkSuccess(Long taskId, ReviewReport report) {
        ReviewReport saved = reviewReportRepository.save(report);
        markSuccess(taskId, saved.getId());
        return saved;
    }

    private AsyncTask loadTask(Long taskId) {
        return asyncTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Async task not found: " + taskId));
    }
}
