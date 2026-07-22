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

        String ext = getExtension(fileName);
        String storedName = UUID.randomUUID().toString().substring(0, 8) + "_" + fileName;
        Path target = dir.resolve(storedName);

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
}
