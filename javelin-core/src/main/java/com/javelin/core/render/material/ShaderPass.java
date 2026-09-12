package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.render.shader.ShaderAST.CompareFunction;
import com.javelin.core.render.shader.ShaderAST.CullMode;
import com.javelin.core.render.shader.ShaderAST.RenderLayer;
import com.javelin.core.render.shader.ShaderAST.RenderPath;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shader Pass 模板 —— 定义一个渲染 Pass 的静态配置。
 *
 * <p>由编辑器导入管线生成（SPIR-V 字节码 + 渲染状态 + 纹理槽），
 * 是 {@link Shader} 资产的组成部分。运行时 {@code GraphicsPipeline} 是
 * ShaderPass × VertexLayout 的组合产物，不由本类持有。</p>
 */
@Reflective
public class ShaderPass implements Serializable {

    /** Pass 名称（如 "Forward"），文件内唯一 */
    private String name;

    /** 顶点着色器 SPIR-V 字节码 */
    private byte[] vertexBytecode;

    /** 片元着色器 SPIR-V 字节码 */
    private byte[] fragmentBytecode;

    /** 深度写入，默认开启 */
    private boolean zWrite = true;

    /** 深度比较函数，默认 LessEqual */
    private CompareFunction zTest = CompareFunction.LessEqual;

    /** 面剔除模式，默认 Back */
    private CullMode cullMode = CullMode.Back;

    /** 渲染路径，默认 Deferred */
    private RenderPath renderPath = RenderPath.Deferred;

    /** 渲染层，默认 Opaque */
    private RenderLayer renderLayer = RenderLayer.Opaque;

    /**
     * 混合状态，null 表示 DSL 未显式声明。
     *
     * <p>消费方按 renderLayer 取默认：Opaque→Off，其余→alphaBlend
     * （DSL 显式声明优先，见 doc/SHADER-DESIGN.md）。</p>
     */
    private BlendDesc blend;

    /** 纹理槽名列表，按 DSL 声明顺序（对应 set=1 binding 顺序） */
    private final List<String> textureSlots = new ArrayList<>();

    public ShaderPass() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Serializable
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void write(OutputCapsule output) {
        output.write("name", name);
        output.write("vertexBytecode", vertexBytecode);
        output.write("fragmentBytecode", fragmentBytecode);
        output.write("zWrite", zWrite);
        output.write("zTest", zTest);
        output.write("cullMode", cullMode);
        output.write("renderPath", renderPath);
        output.write("renderLayer", renderLayer);
        output.write("blend", blend);
        output.writeArrayString("textureSlots", new ArrayList<>(textureSlots));
    }

    @Override
    public void read(InputCapsule input) {
        name = input.readString("name");
        vertexBytecode = input.readByteArray("vertexBytecode");
        fragmentBytecode = input.readByteArray("fragmentBytecode");
        zWrite = input.readBoolean("zWrite");
        zTest = input.readEnum("zTest", CompareFunction.class);
        cullMode = input.readEnum("cullMode", CullMode.class);
        renderPath = input.readEnum("renderPath", RenderPath.class);
        renderLayer = input.readEnum("renderLayer", RenderLayer.class);
        blend = input.readSerializable("blend", BlendDesc.class);
        textureSlots.clear();
        ArrayList<String> slots = input.readArrayString("textureSlots");
        if (slots != null) {
            textureSlots.addAll(slots);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  访问器
    // ═══════════════════════════════════════════════════════════════════════

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public byte[] getVertexBytecode() {
        return vertexBytecode;
    }

    public void setVertexBytecode(byte[] vertexBytecode) {
        this.vertexBytecode = vertexBytecode;
    }

    public byte[] getFragmentBytecode() {
        return fragmentBytecode;
    }

    public void setFragmentBytecode(byte[] fragmentBytecode) {
        this.fragmentBytecode = fragmentBytecode;
    }

    public boolean isZWrite() {
        return zWrite;
    }

    public void setZWrite(boolean zWrite) {
        this.zWrite = zWrite;
    }

    public CompareFunction getZTest() {
        return zTest;
    }

    public void setZTest(CompareFunction zTest) {
        this.zTest = zTest;
    }

    public CullMode getCullMode() {
        return cullMode;
    }

    public void setCullMode(CullMode cullMode) {
        this.cullMode = cullMode;
    }

    public RenderPath getRenderPath() {
        return renderPath;
    }

    public void setRenderPath(RenderPath renderPath) {
        this.renderPath = renderPath;
    }

    public RenderLayer getRenderLayer() {
        return renderLayer;
    }

    public void setRenderLayer(RenderLayer renderLayer) {
        this.renderLayer = renderLayer;
    }

    public BlendDesc getBlend() {
        return blend;
    }

    public void setBlend(BlendDesc blend) {
        this.blend = blend;
    }

    /** 纹理槽名列表（只读），按声明顺序 */
    public List<String> getTextureSlots() {
        return Collections.unmodifiableList(textureSlots);
    }

    public void setTextureSlots(List<String> textureSlots) {
        this.textureSlots.clear();
        if (textureSlots != null) {
            for (String slot : textureSlots) {
                addTextureSlot(slot);
            }
        }
    }

    /** 追加纹理槽名（去重，保持首次声明顺序） */
    public void addTextureSlot(String slot) {
        if (slot != null && !textureSlots.contains(slot)) {
            textureSlots.add(slot);
        }
    }
}
