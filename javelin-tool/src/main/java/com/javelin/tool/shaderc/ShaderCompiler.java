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
     * 释放 shaderc 编译器资源。
     *
     * <p>调用后该实例不可再使用。</p>
     */
    @Override
    public void close() {
        Shaderc.shaderc_compiler_release(compiler);
    }
}
