package com.javelin.tool.shaderc;

import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

/**
 * 着色器编译器。
 *
 * <p>基于 LWJGL shaderc 封装，将 GLSL 源码字符串编译为 SPIR-V 二进制字节数组。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * try (ShaderCompiler compiler = new ShaderCompiler()) {
 *     byte[] spirv = compiler.compile(vertexGlsl, ShaderStage.VERTEX);
 * }
 * }</pre>
 *
 * @author Phillip Webb
 * @since 1.0.0
 */
public class ShaderCompiler implements AutoCloseable {

    private final long compiler;

    /**
     * 创建着色器编译器实例。
     *
     * <p>内部初始化 shaderc 原生编译器对象，创建后应及时调用 {@link #close()} 释放资源。</p>
     */
    public ShaderCompiler() {
        this.compiler = Shaderc.shaderc_compiler_initialize();
        if (this.compiler == 0L) {
            throw new ShaderCompileException("无法初始化 shaderc 编译器");
        }
    }

    /**
     * 编译 GLSL 源码为 SPIR-V。
     *
     * <p>源文件名称默认使用 "shader"，入口函数默认使用 "main"。</p>
     *
     * @param source GLSL 源码字符串
     * @param stage  着色器阶段
     * @return SPIR-V 二进制字节数组
     * @throws ShaderCompileException 编译失败时抛出
     */
    public byte[] compile(String source, ShaderStage stage) {
        return compile(source, stage, "shader", "main");
    }

    /**
     * 编译 GLSL 源码为 SPIR-V。
     *
     * @param source     GLSL 源码字符串
     * @param stage      着色器阶段
     * @param sourceName 源文件名称（用于错误日志定位）
     * @return SPIR-V 二进制字节数组
     * @throws ShaderCompileException 编译失败时抛出
     */
    public byte[] compile(String source, ShaderStage stage, String sourceName) {
        return compile(source, stage, sourceName, "main");
    }

    /**
     * 编译 GLSL 源码为 SPIR-V。
     *
     * @param source     GLSL 源码字符串
     * @param stage      着色器阶段
     * @param sourceName 源文件名称（用于错误日志定位）
     * @param entryPoint 入口函数名称，通常为 "main"
     * @return SPIR-V 二进制字节数组
     * @throws ShaderCompileException 编译失败时抛出
     */
    public byte[] compile(String source, ShaderStage stage, String sourceName, String entryPoint) {
        long result = Shaderc.shaderc_compile_into_spv(
            compiler,
            source,
            stage.getShadercKind(),
            sourceName,
            entryPoint,
            0L
        );

        try {
            if (Shaderc.shaderc_result_get_compilation_status(result)
                != Shaderc.shaderc_compilation_status_success) {
                String error = Shaderc.shaderc_result_get_error_message(result);
                throw new ShaderCompileException(
                    "着色器编译失败 [" + sourceName + "]: " + error
                );
            }

            ByteBuffer spirvBuffer = Shaderc.shaderc_result_get_bytes(result);
            if (spirvBuffer == null) {
                throw new ShaderCompileException(
                    "着色器编译结果为空 [" + sourceName + "]"
                );
            }

            byte[] spirv = new byte[spirvBuffer.remaining()];
            spirvBuffer.get(spirv);
            return spirv;
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }

    /**
     * 编译 GLSL 源码为 SPIR-V（带编译选项）。
     *
     * <p>根据 {@link Options} 生成临时 shaderc 编译选项：
     * 调试信息、优化级别、目标 Vulkan 版本；options 为 null 时行为与
     * {@link #compile(String, ShaderStage, String, String)} 相同。</p>
     *
     * @param source     GLSL 源码字符串
     * @param stage      着色器阶段
     * @param sourceName 源文件名称（用于错误日志定位）
     * @param options    编译选项，可为 null
     * @return SPIR-V 二进制字节数组
     * @throws ShaderCompileException 编译失败时抛出
     */
    public byte[] compile(String source, ShaderStage stage, String sourceName, Options options) {
        long compileOptions = 0L;
        if (options != null) {
            compileOptions = Shaderc.shaderc_compile_options_initialize();
            if (compileOptions == 0L) {
                throw new ShaderCompileException("无法初始化 shaderc 编译选项");
            }
            if (options.generateDebugInfo) {
                Shaderc.shaderc_compile_options_set_generate_debug_info(compileOptions);
            }
            Shaderc.shaderc_compile_options_set_optimization_level(compileOptions, options.optimization.shadercLevel);
            Shaderc.shaderc_compile_options_set_target_env(compileOptions,
                Shaderc.shaderc_target_env_vulkan, options.targetVulkanVersion);
        }

        long result;
        try {
            result = Shaderc.shaderc_compile_into_spv(
                compiler,
                source,
                stage.getShadercKind(),
                sourceName,
                "main",
                compileOptions
            );
        } finally {
            if (compileOptions != 0L) {
                Shaderc.shaderc_compile_options_release(compileOptions);
            }
        }

        try {
            if (Shaderc.shaderc_result_get_compilation_status(result)
                != Shaderc.shaderc_compilation_status_success) {
                String error = Shaderc.shaderc_result_get_error_message(result);
                throw new ShaderCompileException(
                    "着色器编译失败 [" + sourceName + "]: " + error
                );
            }

            ByteBuffer spirvBuffer = Shaderc.shaderc_result_get_bytes(result);
            if (spirvBuffer == null) {
                throw new ShaderCompileException(
                    "着色器编译结果为空 [" + sourceName + "]"
                );
            }

            byte[] spirv = new byte[spirvBuffer.remaining()];
            spirvBuffer.get(spirv);
            return spirv;
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  编译选项
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 优化级别（映射 shaderc 的三档优化）。
     */
    public enum Optimization {
        /** 不优化（保留最多调试信息） */
        NONE(Shaderc.shaderc_optimization_level_zero),
        /** 体积优先优化 */
        SIZE(Shaderc.shaderc_optimization_level_size),
        /** 性能优先优化（默认） */
        PERFORMANCE(Shaderc.shaderc_optimization_level_performance);

        final int shadercLevel;

        Optimization(int shadercLevel) {
            this.shadercLevel = shadercLevel;
        }
    }

    /**
     * 编译选项。
     *
     * <p>链式 setter；{@link #targetVulkanVersion(String)} 接受
     * {@code "vulkan1.0"} ~ {@code "vulkan1.4"}（大小写不敏感），默认 1.3。</p>
     */
    public static final class Options {
        private boolean generateDebugInfo = false;
        private Optimization optimization = Optimization.PERFORMANCE;
        private int targetVulkanVersion = Shaderc.shaderc_env_version_vulkan_1_3;

        public boolean isGenerateDebugInfo() {
            return generateDebugInfo;
        }

        public Options generateDebugInfo(boolean generateDebugInfo) {
            this.generateDebugInfo = generateDebugInfo;
            return this;
        }

        public Optimization getOptimization() {
            return optimization;
        }

        public Options optimization(Optimization optimization) {
            this.optimization = optimization != null ? optimization : Optimization.PERFORMANCE;
            return this;
        }

        public int getTargetVulkanVersion() {
            return targetVulkanVersion;
        }

        /** 接受 "vulkan1.0" ~ "vulkan1.4"（大小写不敏感），默认 1.3 */
        public Options targetVulkanVersion(String version) {
            if (version == null || version.isBlank()) {
                this.targetVulkanVersion = Shaderc.shaderc_env_version_vulkan_1_3;
                return this;
            }
            this.targetVulkanVersion = switch (version.trim().toLowerCase()) {
                case "vulkan1.0" -> Shaderc.shaderc_env_version_vulkan_1_0;
                case "vulkan1.1" -> Shaderc.shaderc_env_version_vulkan_1_1;
                case "vulkan1.2" -> Shaderc.shaderc_env_version_vulkan_1_2;
                case "vulkan1.3" -> Shaderc.shaderc_env_version_vulkan_1_3;
                case "vulkan1.4" -> Shaderc.shaderc_env_version_vulkan_1_4;
                default -> throw new IllegalArgumentException("不支持的目标 Vulkan 版本: " + version);
            };
            return this;
        }
    }

    /**
     * 释放 shaderc 编译器资源。
     *
     * <p>调用后该实例不可再使用。</p>
     */
    @Override
    public void close() {
        Shaderc.shaderc_compiler_release(compiler);
    }
}
