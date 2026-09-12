package com.javelin.editor.importer.shader;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.editor.importer.ImportSettings;

/**
 * 着色器导入设置。
 *
 * <p>控制 .shader 文件的导入行为，全部字段真正传入编译器生效
 * （修正 engine4 中设置存在但未传入 ShaderCompiler 的遗留问题）：</p>
 * <ul>
 *   <li>{@link #optimizationLevel} → shaderc 优化级别（NONE/STANDARD/MAXIMUM）</li>
 *   <li>{@link #targetVulkanVersion} → shaderc 目标环境版本（"vulkan1.0"~"vulkan1.4"）</li>
 *   <li>{@link #generateDebugInfo} → shaderc 调试信息生成</li>
 *   <li>{@link #preservePreprocessedSource} → 预处理 GLSL 落盘到源目录 .shader-debug/</li>
 * </ul>
 */
@Reflective
public class ShaderImportSettings extends ImportSettings {

    /**
     * 编译优化级别。
     *
     * <p>shaderc 只有 zero/size/performance 三档，映射为
     * NONE→zero、STANDARD→performance、MAXIMUM→performance。</p>
     */
    public enum OptimizationLevel {
        /** 无优化，保留最多调试信息 */
        NONE,
        /** 标准优化 */
        STANDARD,
        /** 最大优化 */
        MAXIMUM
    }

    /** 是否生成调试信息 */
    private boolean generateDebugInfo = false;

    /** 优化级别 */
    private OptimizationLevel optimizationLevel = OptimizationLevel.STANDARD;

    /** 目标 Vulkan 版本（"vulkan1.0" ~ "vulkan1.4"） */
    private String targetVulkanVersion = "vulkan1.3";

    /** 是否保留预处理后的 GLSL 源码（写到源目录 .shader-debug/） */
    private boolean preservePreprocessedSource = false;

    public ShaderImportSettings() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  访问器
    // ═══════════════════════════════════════════════════════════════════════

    public boolean isGenerateDebugInfo() {
        return generateDebugInfo;
    }

    public void setGenerateDebugInfo(boolean generateDebugInfo) {
        this.generateDebugInfo = generateDebugInfo;
    }

    public OptimizationLevel getOptimizationLevel() {
        return optimizationLevel;
    }

    public void setOptimizationLevel(OptimizationLevel optimizationLevel) {
        this.optimizationLevel = optimizationLevel;
    }

    public String getTargetVulkanVersion() {
        return targetVulkanVersion;
    }

    public void setTargetVulkanVersion(String targetVulkanVersion) {
        this.targetVulkanVersion = targetVulkanVersion;
    }

    public boolean isPreservePreprocessedSource() {
        return preservePreprocessedSource;
    }

    public void setPreservePreprocessedSource(boolean preservePreprocessedSource) {
        this.preservePreprocessedSource = preservePreprocessedSource;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ImportSettings 实现
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public String getType() {
        return "Shader";
    }

    @Override
    public boolean validate() {
        return targetVulkanVersion != null && !targetVulkanVersion.isEmpty();
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("generateDebugInfo", generateDebugInfo);
        output.write("optimizationLevel", optimizationLevel);
        output.write("targetVulkanVersion", targetVulkanVersion);
        output.write("preservePreprocessedSource", preservePreprocessedSource);
    }

    @Override
    public void read(InputCapsule input) {
        generateDebugInfo = input.readBoolean("generateDebugInfo");
        optimizationLevel = input.readEnum("optimizationLevel", OptimizationLevel.class);
        targetVulkanVersion = input.readString("targetVulkanVersion");
        preservePreprocessedSource = input.readBoolean("preservePreprocessedSource");
    }
}
