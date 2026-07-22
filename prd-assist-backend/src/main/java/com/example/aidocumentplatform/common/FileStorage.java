package com.example.aidocumentplatform.common;

/**
 * 文件存储抽象接口。
 *
 * 当前实现：LocalFileStorage（本地文件系统）
 * 未来可切换：MinIOFileStorage / AliyunOssFileStorage，不影响业务代码。
 */
public interface FileStorage {

    /**
     * 存储文件并返回访问路径。
     *
     * @param bytes    文件字节数组
     * @param fileName 原始文件名（用于提取扩展名）
     * @return 文件存储路径或 URL
     */
    String store(byte[] bytes, String fileName);

    /**
     * 删除已存储的文件。
     *
     * @param path 存储时返回的路径
     */
    void delete(String path);
}
