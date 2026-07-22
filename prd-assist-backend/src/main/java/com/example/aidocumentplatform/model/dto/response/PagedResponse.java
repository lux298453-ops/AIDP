package com.example.aidocumentplatform.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 分页响应包装。避免 Spring Data PageImpl 序列化问题。 */
@Data
@Builder
@AllArgsConstructor
public class PagedResponse<T> {
    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int number;
    private int size;
}
