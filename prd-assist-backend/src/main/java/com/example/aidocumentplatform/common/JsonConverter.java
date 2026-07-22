package com.example.aidocumentplatform.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA JSON 字段转换器。
 * 实体中 String 类型的字段标注为 JSON 列时，用此 Converter 做序列化/反序列化。
 * 用法: @Convert(converter = JsonConverter.class)
 */
@Converter
public class JsonConverter implements AttributeConverter<String, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        // 确保写入的是合法 JSON: 如果已经是 JSON 字符串直接返回，否则包一层
        try {
            objectMapper.readTree(attribute);
            return attribute;
        } catch (JsonProcessingException e) {
            // 不是 JSON 格式，转成 JSON 字符串存储
            try {
                return objectMapper.writeValueAsString(attribute);
            } catch (JsonProcessingException ex) {
                return attribute;
            }
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData;
    }
}
