package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.assets.AbsAsset;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shader 资产 —— Pass 模板列表 + 可暴露属性定义。
 *
 * <p>由编辑器导入管线从 .shader 源文件生成（解析 → 预处理 → shaderc 编译 SPIR-V），
 * 序列化为 .asset 二进制。<b>运行时只加载二进制，不接触原始 GLSL 源码</b>，
 * {@code javelin-core} 不依赖 shaderc（{@code javelin-tool} 只存在于编辑器侧）。</p>
 */
@Reflective
public class Shader extends AbsAsset {

    /** Pass 模板列表 */
    private final List<ShaderPass> passes = new ArrayList<>();

    /** 可暴露属性定义列表 */
    private final List<ShaderProperty> properties = new ArrayList<>();

    public Shader() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Pass 管理
    // ═══════════════════════════════════════════════════════════════════════

    public void addPass(ShaderPass pass) {
        passes.add(pass);
    }

    public List<ShaderPass> getPasses() {
        return Collections.unmodifiableList(passes);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Property 管理
    // ═══════════════════════════════════════════════════════════════════════

    public void addProperty(ShaderProperty property) {
        properties.add(property);
    }

    public List<ShaderProperty> getProperties() {
        return Collections.unmodifiableList(properties);
    }

    /**
     * 按名称查找属性定义。
     *
     * @param name 属性名
     * @return 属性定义，未找到返回 null
     */
    public ShaderProperty getProperty(String name) {
        for (ShaderProperty p : properties) {
            if (p.getName().equals(name)) {
                return p;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Serializable
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("passes", new ArrayList<>(passes));
        output.write("properties", new ArrayList<>(properties));
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        passes.clear();
        ArrayList<ShaderPass> readPasses = input.readArrayList("passes", ShaderPass.class);
        if (readPasses != null) {
            passes.addAll(readPasses);
        }
        properties.clear();
        ArrayList<ShaderProperty> readProps = input.readArrayList("properties", ShaderProperty.class);
        if (readProps != null) {
            properties.addAll(readProps);
        }
    }
}
