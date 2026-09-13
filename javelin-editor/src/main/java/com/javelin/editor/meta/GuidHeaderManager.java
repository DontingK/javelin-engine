package com.javelin.editor.meta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/**
 * 文件头 GUID 管理器
 *
 * <p>负责文本资源文件头 GUID 的提取、注入和修复。</p>
 * <p>适用于 {@code .java}、{@code .shader} 等支持行注释的文本资源。</p>
 *
 * @author donting
 * @since 1.0
 */
public class GuidHeaderManager {

    private static final Logger logger = LoggerFactory.getLogger(GuidHeaderManager.class);

    /**
     * GUID 注释前缀
     */
    public static final String GUID_PREFIX = "// @javelin-guid:";

    /**
     * 从文件提取 GUID。如果不存在，自动注入。
     *
     * @param file 资源文件
     * @return GUID（可能刚生成）
     */
    public String extractOrInjectGuid(File file) {
        String guid = extractGuid(file);
        if (guid == null || guid.isEmpty()) {
            guid = generateGuid();
            injectGuid(file, guid);
        }
        return guid;
    }

    /**
     * 从文件提取 GUID。如果不存在，使用路径种子生成确定性 GUID 并注入。
     *
     * @param file     资源文件
     * @param pathSeed 路径种子（用于生成确定性 GUID）
     * @return GUID
     */
    public String extractOrInjectGuid(File file, String pathSeed) {
        String guid = extractGuid(file);
        if (guid == null || guid.isEmpty()) {
            if (pathSeed != null && !pathSeed.isEmpty()) {
                guid = UUID.nameUUIDFromBytes(pathSeed.getBytes(StandardCharsets.UTF_8))
                        .toString().replace("-", "");
            } else {
                guid = generateGuid();
            }
            injectGuid(file, guid);
        }
        return guid;
    }

    /**
     * 提取 GUID，不自动注入
     *
     * @param file 资源文件
     * @return GUID，不存在返回 null
     */
    public String extractGuid(File file) {
        if (!file.exists() || !file.isFile()) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return null;
            }
            String firstLine = lines.get(0).trim();
            if (firstLine.startsWith(GUID_PREFIX)) {
                return firstLine.substring(GUID_PREFIX.length()).trim();
            }
        } catch (IOException e) {
            logger.error("读取文件头 GUID 失败: {}", file, e);
        }
        return null;
    }

    /**
     * 注入或更新文件头中的 GUID
     *
     * @param file 资源文件
     * @param guid GUID
     */
    public void injectGuid(File file, String guid) {
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            String guidLine = GUID_PREFIX + " " + guid;

            if (!lines.isEmpty() && lines.get(0).trim().startsWith(GUID_PREFIX)) {
                lines.set(0, guidLine);
            } else {
                lines.add(0, guidLine);
            }

            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
            logger.info("注入文件头 GUID: {} [{}]", file.getName(), guid);
        } catch (IOException e) {
            logger.error("注入文件头 GUID 失败: {}", file, e);
        }
    }

    /**
     * 生成随机 GUID
     *
     * @return GUID 字符串
     */
    private String generateGuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
