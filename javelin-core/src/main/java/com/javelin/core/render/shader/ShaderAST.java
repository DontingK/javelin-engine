package com.javelin.core.render.shader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shader DSL AST（抽象语法树）节点定义。
 *
 * <p>由 {@link ShaderParser} 解析 .shader 源文件后生成，
 * 供编辑器导入管线遍历并编译为 SPIR-V。节点为纯字段的 POJO，
 * 渲染状态使用本包内自包含的枚举，不依赖 RHI 类型。</p>
 */
public final class ShaderAST {

    private ShaderAST() {
        // 工具类，禁止实例化
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  自包含枚举（与 DSL 文本取值一一对应）
    // ═══════════════════════════════════════════════════════════════════════

    /** 渲染路径 */
    public enum RenderPath {
        Forward,
        Deferred,
        Shadow
    }

    /** 渲染层 */
    public enum RenderLayer {
        Opaque,
        Transparent,
        Overlay
    }

    /** 面剔除模式 */
    public enum CullMode {
        Back,
        Front,
        None
    }

    /** 深度比较函数，Off 表示关闭深度测试 */
    public enum CompareFunction {
        Off,
        Never,
        Less,
        LessEqual,
        Equal,
        Greater,
        GreaterEqual,
        Always
    }

    /** 混合因子（自包含，不依赖 RHI 的 BlendFactor） */
    public enum BlendFactor {
        Zero,
        One,
        SrcColor,
        OneMinusSrcColor,
        DstColor,
        OneMinusDstColor,
        SrcAlpha,
        OneMinusSrcAlpha,
        DstAlpha,
        OneMinusDstAlpha
    }

    /** 着色器阶段类型 */
    public enum StageType {
        VERTEX,
        FRAGMENT
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  根节点
    // ═══════════════════════════════════════════════════════════════════════

    /** Shader 根节点 */
    public static class ShaderNode {
        /** Shader 名称（如 "Default"） */
        public String name;
        /** 从文件头注释 {@code // @javelin-guid:} 提取的 GUID，缺失为 null */
        public String guid;
        /** Properties 块，未声明为 null */
        public PropertiesNode properties;
        /** GlobalUniforms 块，未声明为 null */
        public GlobalUniformsNode globalUniforms;
        /** Pass 列表 */
        public final List<PassNode> passes = new ArrayList<>();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Properties
    // ═══════════════════════════════════════════════════════════════════════

    /** Properties 块节点 */
    public static class PropertiesNode {
        public final List<PropertyNode> properties = new ArrayList<>();
    }

    /** 单个属性定义节点 */
    public static class PropertyNode {
        /** 属性标识符（如 "_Color"） */
        public String name;
        /** 显示名称（如 "Tint"） */
        public String displayName;
        /** 属性类型名（如 "Color"、"Range"、"Texture2D"） */
        public String type;
        /** Range 下限，非 Range 类型为 null */
        public Float rangeMin;
        /** Range 上限，非 Range 类型为 null */
        public Float rangeMax;
        /** 默认值：Float/Number/Boolean/float[]（向量字面量）/String（纹理） */
        public Object defaultValue;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  GlobalUniforms
    // ═══════════════════════════════════════════════════════════════════════

    /** GlobalUniforms 块节点 */
    public static class GlobalUniformsNode {
        /** PerFrame Uniform 块 */
        public UniformBlockNode perFrame;
        /** PerObject Uniform 块 */
        public UniformBlockNode perObject;
    }

    /** Uniform 块声明（如 JavelinPerFrame { ... }） */
    public static class UniformBlockNode {
        /** 块名称 */
        public String name;
        /** 字段列表 */
        public final List<UniformFieldNode> fields = new ArrayList<>();
    }

    /** Uniform 字段声明 */
    public static class UniformFieldNode {
        /** GLSL 类型（如 "mat4"、"vec3"、"float"） */
        public String type;
        /** 字段名 */
        public String name;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Pass
    // ═══════════════════════════════════════════════════════════════════════

    /** Pass 块节点 */
    public static class PassNode {
        /** Pass 名称（如 "Forward"） */
        public String name;
        /** 渲染路径，默认 Deferred */
        public RenderPath renderPath = RenderPath.Deferred;
        /** 渲染层，默认 Opaque */
        public RenderLayer renderLayer = RenderLayer.Opaque;
        /** 深度写入，默认开启 */
        public boolean zWrite = true;
        /** 深度比较，默认 LessEqual */
        public CompareFunction zTest = CompareFunction.LessEqual;
        /** 面剔除，默认 Back */
        public CullMode cullMode = CullMode.Back;
        /** 混合状态，默认 Off */
        public BlendStateNode blend = new BlendStateNode();
        /** 是否显式声明了 blend（区分"未声明"与"声明为 Off"，导入器据此决定 ShaderPass.blend 是否为 null） */
        public boolean blendDeclared = false;
        /** 顶点着色器代码，未声明为 null */
        public ShaderStageNode vertexShader;
        /** 片元着色器代码，未声明为 null */
        public ShaderStageNode fragmentShader;
    }

    /** 混合状态节点 */
    public static class BlendStateNode {
        /** 是否启用混合 */
        public boolean enabled = false;
        /** 源混合因子 */
        public BlendFactor srcFactor = BlendFactor.One;
        /** 目标混合因子 */
        public BlendFactor dstFactor = BlendFactor.Zero;
    }

    /** 着色器阶段节点（vs / fs） */
    public static class ShaderStageNode {
        /** 阶段类型 */
        public final StageType type;
        /** 原始 GLSL 源码（预处理前） */
        public String source;

        public ShaderStageNode(StageType type) {
            this.type = type;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 从 AST 中提取所有 Texture2D 类型的属性名（用于 textureSlots），
     * 按声明顺序去重。
     *
     * @param shader Shader 根节点
     * @return 纹理属性名集合
     */
    public static Set<String> extractTextureSlots(ShaderNode shader) {
        Set<String> slots = new LinkedHashSet<>();
        if (shader.properties != null) {
            for (PropertyNode prop : shader.properties.properties) {
                if ("Texture2D".equals(prop.type)) {
                    slots.add(prop.name);
                }
            }
        }
        return slots;
    }

    /**
     * 查找指定名称的 Pass。
     *
     * @param shader Shader 根节点
     * @param name   Pass 名称
     * @return Pass 节点，未找到返回 null
     */
    public static PassNode findPass(ShaderNode shader, String name) {
        for (PassNode pass : shader.passes) {
            if (pass.name.equals(name)) {
                return pass;
            }
        }
        return null;
    }

    /**
     * 查找指定名称的属性。
     *
     * @param shader Shader 根节点
     * @param name   属性名
     * @return 属性节点，未找到返回 null
     */
    public static PropertyNode findProperty(ShaderNode shader, String name) {
        if (shader.properties == null) return null;
        for (PropertyNode prop : shader.properties.properties) {
            if (prop.name.equals(name)) {
                return prop;
            }
        }
        return null;
    }
}
