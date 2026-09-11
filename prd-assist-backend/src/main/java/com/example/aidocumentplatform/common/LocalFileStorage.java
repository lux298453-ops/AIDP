package com.example.aidocumentplatform.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地文件系统实现。
 *
 * 文件组织: {baseDir}/{yyyy-MM}/{uuid}_{originalName}
 * 后续替换为 MinIO/OSS 时只需新建一个实现类并注入。
 */
@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

    private final Path baseDir;

    public LocalFileStorage(@Value("${app.upload.dir:uploads}") String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath();
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建上传目录: " + this.baseDir, e);
        }
    }

    @Override
    public String store(byte[] bytes, String fileName) {
        String dateDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Path dir = baseDir.resolve(dateDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建目录: " + dir, e);
        }

        // 存储名只使用 UUID + 白名单扩展名，绝不拼接客户端原始文件名，防止路径穿越
        String storedName = UUID.randomUUID() + sanitizeExtension(getExtension(fileName));
        Path target = dir.resolve(storedName).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("非法文件存储路径: " + fileName);
        }

        try {
            Files.copy(
                    new java.io.ByteArrayInputStream(bytes),
                    target,
                    StandardCopyOption.REPLACE_EXISTING
            );
            log.info("文件已保存: {}", target);
            return target.toString();
        } catch (IOException e) {
            throw new RuntimeException("文件存储失败: " + fileName, e);
        }
    }

    @Override
    public void delete(String path) {
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            log.warn("文件删除失败: {}", path, e);
        }
    }

    private String getExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }

    private static final java.util.Set<String> ALLOWED_EXTENSIONS = java.util.Set.of(
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg",
            ".md", ".txt", ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".html", ".json");

    private String sanitizeExtension(String ext) {
        if (ext == null) return "";
        String normalized = ext.trim().toLowerCase();
        return ALLOWED_EXTENSIONS.contains(normalized) ? normalized : "";
    }
}
