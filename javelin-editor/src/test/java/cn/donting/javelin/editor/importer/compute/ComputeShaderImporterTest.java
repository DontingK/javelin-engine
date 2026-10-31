package cn.donting.javelin.editor.importer.compute;

import cn.donting.javelin.core.render.compute.ComputeShader;
import cn.donting.javelin.tool.shaderc.ShaderCompileException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ComputeShaderImporter 单测：.compute 源码 → tool shaderc COMPUTE 编译
 * SPIR-V → 装配 ComputeShader（真实编译）。
 *
 * <p>源码内联（PushUniformTest 先例）——RHI 流程验证归 core 侧 ComputeSsboTest
 * （其 test resources 自有 .compute 文件），本测试只验导入器装配面（资产名解析/
 * 编译产物/坏源显性抛），两模块各持源码零共享（test 源集不跨模块可见）。</p>
 */
public class ComputeShaderImporterTest {

    private static final String VALID_COMPUTE = """
            #version 450
            layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
            layout(std430, set = 0, binding = 0) writeonly buffer Data {
                vec4 elements[];
            } data;
            void main() {
                data.elements[gl_GlobalInvocationID.x] = vec4(1.0, 0.0, 0.0, 1.0);
            }
            """;

    private static final String BAD_COMPUTE = """
            #version 450
            void main() { this is not glsl }
            """;

    @Test
    public void buildComputeShaderEndToEnd() {
        ComputeShader shader = ComputeShaderImporter.buildComputeShader(
                VALID_COMPUTE, "ssbo-write-a.compute");

        assertEquals("ssbo-write-a", shader.getName(), "资产名 = 文件名去扩展名");
        assertTrue(shader.isValid(), "SPIR-V 产物为空");
        assertValidSpirv(shader.getSpirv());
    }

    @Test
    public void invalidSourceThrowsCompileException() {
        // 坏源显性抛（错误显性化，红线 15；stub 落盘归 importAsset 管线职责，build 侧不吞）
        assertThrows(ShaderCompileException.class,
                () -> ComputeShaderImporter.buildComputeShader(BAD_COMPUTE, "bad.compute"));
    }

    private static void assertValidSpirv(byte[] spirv) {
        assertTrue(spirv != null && spirv.length > 100, "SPIR-V 产物为空");
        byte[] magic = {0x03, 0x02, 0x23, 0x07};
        assertArrayEquals(magic, new byte[]{spirv[0], spirv[1], spirv[2], spirv[3]},
                "SPIR-V magic number 不合法");
    }
}
