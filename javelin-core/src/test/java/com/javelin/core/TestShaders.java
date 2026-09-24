package com.javelin.core;

import com.javelin.tool.shaderc.ShaderCompiler;
import com.javelin.tool.shaderc.ShaderStage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 测试侧着色器编译工具：着色器源码放 test resources（core 主代码不出现 GLSL），
 * 经本类读资源 + shaderc 编译为 SPIR-V。
 *
 * <p>用法：{@code TestShaders.compileResource("shaders/builtin_quad.vert", ShaderStage.VERTEX)}。
 * RhiTestBase 暴露同能力的实例方法 {@link RhiTestBase#compileShaderResource}；
 * 非 RhiTestBase 子类（如 JavelinEngine 子类 Demo）直接用本工具类。
 */
public final class TestShaders {

    private TestShaders() {
    }

    /** 读 classpath 着色器资源并编译为 SPIR-V（每次调用创建一个临时 ShaderCompiler） */
    public static byte[] compileResource(String resourcePath, ShaderStage stage) {
        String source = readResource(resourcePath);
        try (ShaderCompiler compiler = new ShaderCompiler()) {
            return compiler.compile(source, stage, resourcePath);
        }
    }

    /** 读取 classpath 文本资源（UTF-8） */
    public static String readResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            throw new IllegalArgumentException("resourcePath must not be empty");
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = TestShaders.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("classpath 资源不存在: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取着色器资源失败: " + resourcePath, e);
        }
    }
}
