package com.example.aidocumentplatform.util;

import java.util.Set;

public class FileUtils {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "xmind", "doc", "docx", "pdf", "txt", "md"
    );

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB

    public static boolean isAllowedExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) return false;
        String ext = getExtension(fileName);
        return ext != null && ALLOWED_EXTENSIONS.contains(ext.toLowerCase());
    }

    public static boolean isFileSizeValid(long fileSize) {
        return fileSize > 0 && fileSize <= MAX_FILE_SIZE;
    }

    public static String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) return null;
        return fileName.substring(dotIndex + 1);
    }

    public static String getContentType(String extension) {
        return switch (extension.toLowerCase()) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xmind" -> "application/octet-stream";
            case "txt", "md" -> "text/plain";
            default -> "application/octet-stream";
        };
    }
}
