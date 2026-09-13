package com.javelin.editor.importer.shader;

import com.javelin.core.render.material.BlendDesc;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.material.ShaderPass;
import com.javelin.core.render.material.ShaderProperty;
import com.javelin.core.render.material.ShaderPropertyType;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.editor.importer.AssetImporter;
import com.javelin.editor.importer.ImportSettings;
import com.javelin.editor.importer.ImportTask;
import com.javelin.editor.meta.GuidHeaderManager;
import com.javelin.editor.project.Project;
import com.javelin.tool.shaderc.ShaderCompileException;
import com.javelin.tool.shaderc.ShaderCompiler;
import com.javelin.tool.shaderc.ShaderStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 着色器资源导入器 —— 将 .shader 文件导入为引擎 Shader 资产。
 *
 * <p>导入流程（doc/SHADER-DESIGN.md"编辑器导入管线"）：</p>
 * <ol>
 *   <li>读取 .shader 源文件，确保文件头 {@code // @javelin-guid:} 与 meta GUID 一致（缺失时写回）</li>
 *   <li>DSL 解析为 AST（{@link ShaderParser}，Range 上下限保留）</li>
 *   <li>构建 Shader 资产：属性表 + 每 Pass 渲染状态（blend 未声明时留 null）</li>
 *   <li>每 Pass 的 vs/fs：GLSL 预处理（默认 uniform 块注入、MaterialParams/sampler 生成、
 *       基于源文件目录的相对路径 {@code #include} 展开），失败抛带文件名的异常</li>
 *   <li>shaderc 编译 SPIR-V（选项来自 {@link ShaderImportSettings}，真正生效）</li>
 *   <li>序列化为 Library 下的 .asset 二进制</li>
 * </ol>
 *
 * <p>由 {@code AssetImportPipeline} 通过类扫描自动注册（支持扩展名 .shader）。</p>
 */
public class ShaderImporter implements AssetImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderImporter.class);

    /** 支持的文件扩展名 */
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".shader");

    private final GuidHeaderManager guidHeaderManager = new GuidHeaderManager();

    @Override
    public String getName() {
        return "ShaderImporter";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public ImportSettings createDefaultSettings(File file) {
        return new ShaderImportSettings();
    }

    @Override
    public boolean importAsset(ImportTask task, Project project) throws Exception {
        File sourceFile = task.getSourceFile();
        LOGGER.info("开始导入着色器: {}", sourceFile.getName());

        // 1. 读取源文件
        String source = Files.readString(sourceFile.toPath(), StandardCharsets.UTF_8);

        // 2. GUID：以 meta 为准，缺失时生成；保证文件头注释与 meta 一致（写回头注释）
        String guid = task.getMeta().getGuid();
        if (guid == null || guid.isEmpty()) {
            guid = guidHeaderManager.extractOrInjectGuid(sourceFile);
            task.getMeta().setGuid(guid);
        }
        String headerGuid = guidHeaderManager.extractGuid(sourceFile);
        if (!guid.equals(headerGuid)) {
            guidHeaderManager.injectGuid(sourceFile, guid);
        }

        // 3. DSL 解析
        ShaderAST.ShaderNode ast;
        try {
            ast = new ShaderParser().parse(source);
        } catch (ShaderParseException e) {
            LOGGER.error("着色器解析失败 [{}]: {}", sourceFile.getName(), e.getMessage());
            task.setErrorMessage("解析失败: " + e.getMessage());
            return false;
        }

        // 4. 构建 Shader 资产
        Shader shader = new Shader();
        shader.setGuid(guid);
        shader.setLastModified(sourceFile.lastModified());
        buildProperties(ast, shader);

        // 5. 编译每个 Pass（预处理 + shaderc）
        ShaderImportSettings settings = (ShaderImportSettings) task.getSettings();
        ShaderPreprocessor preprocessor = new ShaderPreprocessor();
        Set<String> textureSlots = ShaderAST.extractTextureSlots(ast);

        try (ShaderCompiler compiler = new ShaderCompiler()) {
            ShaderCompiler.Options compileOptions = toCompileOptions(settings);
            for (ShaderAST.PassNode passNode : ast.passes) {
                ShaderPass shaderPass = compilePass(
                    passNode, ast, preprocessor, compiler, compileOptions, textureSlots, settings, sourceFile);
                if (shaderPass != null) {
                    shader.addPass(shaderPass);
                }
            }
        }

        if (shader.getPasses().isEmpty()) {
            LOGGER.error("着色器 '{}' 没有成功编译的 Pass", ast.name);
            task.setErrorMessage("没有成功编译的 Pass");
            return false;
        }

        // 6. 序列化为 .asset 文件
        File outputFile = getOutputFile(task, project);
        new BinaryExporter(outputFile).export(shader);

        LOGGER.info("着色器导入成功: {} -> {}", sourceFile.getName(), outputFile.getName());
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  内部方法
    // ═══════════════════════════════════════════════════════════════════════

    /** 从 AST Properties 构建 ShaderProperty（Range 上下限入库） */
    private void buildProperties(ShaderAST.ShaderNode ast, Shader shader) {
        if (ast.properties == null) {
            return;
        }
        for (ShaderAST.PropertyNode node : ast.properties.properties) {
            ShaderPropertyType type = mapPropertyType(node.type);
            if (type == null) {
                LOGGER.warn("未知属性类型 '{}', 跳过属性 '{}'", node.type, node.name);
                continue;
            }
            shader.addProperty(ShaderProperty.builder()
                .name(node.name)
                .displayName(node.displayName)
                .type(type)
                .defaultValue(node.defaultValue)
                .rangeMin(node.rangeMin)
                .rangeMax(node.rangeMax)
                .build());
        }
    }

    /**
     * 编译单个 Pass：vs/fs 预处理 + shaderc 编译。
     *
     * <p>任一阶段失败记录日志并返回 null（该 Pass 被跳过）。</p>
     */
    private ShaderPass compilePass(ShaderAST.PassNode passNode,
                                   ShaderAST.ShaderNode ast,
                                   ShaderPreprocessor preprocessor,
                                   ShaderCompiler compiler,
                                   ShaderCompiler.Options compileOptions,
                                   Set<String> textureSlots,
                                   ShaderImportSettings settings,
                                   File sourceFile) {
        ShaderPass shaderPass = new ShaderPass();
        shaderPass.setName(passNode.name);
        shaderPass.setZWrite(passNode.zWrite);
        shaderPass.setZTest(passNode.zTest);
        shaderPass.setCullMode(passNode.cullMode);
        shaderPass.setRenderPath(passNode.renderPath);
        shaderPass.setRenderLayer(passNode.renderLayer);
        shaderPass.setTextureSlots(new ArrayList<>(textureSlots));

        // blend：DSL 显式声明才入库，未声明留 null（由消费方按 renderLayer 取默认）
        if (passNode.blendDeclared) {
            shaderPass.setBlend(new BlendDesc(
                passNode.blend.enabled, passNode.blend.srcFactor, passNode.blend.dstFactor));
        }

        // #include 解析：基于源文件目录的相对路径读取
        ShaderPreprocessor.IncludeResolver includeResolver = createIncludeResolver(sourceFile);

        // 顶点着色器
        if (passNode.vertexShader != null) {
            String label = sourceFile.getName() + ":" + passNode.name + ":vs";
            String processed = preprocessStage(
                passNode.vertexShader.source, ast, preprocessor, includeResolver, label);
            if (processed == null) {
                return null;
            }
            if (settings.isPreservePreprocessedSource()) {
                savePreprocessedSource(sourceFile, passNode.name, "vert", processed);
            }
            try {
                shaderPass.setVertexBytecode(
                    compiler.compile(processed, ShaderStage.VERTEX, label, compileOptions));
            } catch (ShaderCompileException e) {
                LOGGER.error("顶点着色器编译失败 [{}]: {}", label, e.getMessage());
                return null;
            }
        }

        // 片元着色器
        if (passNode.fragmentShader != null) {
            String label = sourceFile.getName() + ":" + passNode.name + ":fs";
            String processed = preprocessStage(
                passNode.fragmentShader.source, ast, preprocessor, includeResolver, label);
            if (processed == null) {
                return null;
            }
            if (settings.isPreservePreprocessedSource()) {
                savePreprocessedSource(sourceFile, passNode.name, "frag", processed);
            }
            try {
                shaderPass.setFragmentBytecode(
                    compiler.compile(processed, ShaderStage.FRAGMENT, label, compileOptions));
            } catch (ShaderCompileException e) {
                LOGGER.error("片元着色器编译失败 [{}]: {}", label, e.getMessage());
                return null;
            }
        }

        return shaderPass;
    }

    /**
     * 预处理单个阶段的 GLSL 源码。
     *
     * @return 预处理后的源码；失败时记录带文件名的错误并返回 null
     */
    private String preprocessStage(String stageSource,
                                   ShaderAST.ShaderNode ast,
                                   ShaderPreprocessor preprocessor,
                                   ShaderPreprocessor.IncludeResolver includeResolver,
                                   String label) {
        try {
            return preprocessor.preprocess(stageSource, ast.properties, ast.globalUniforms, includeResolver);
        } catch (ShaderParseException e) {
            LOGGER.error("着色器预处理失败 [{}]: {}", label, e.getMessage());
            return null;
        }
    }

    /**
     * 创建 include 解析器：相对路径基于源文件所在目录读取。
     *
     * <p>文件不存在时返回 null，由 {@link ShaderPreprocessor} 抛出
     * 带行号的 {@link ShaderParseException}。</p>
     */
    private ShaderPreprocessor.IncludeResolver createIncludeResolver(File sourceFile) {
        File baseDir = sourceFile.getParentFile();
        return path -> {
            File includeFile = new File(baseDir, path);
            if (includeFile.isFile()) {
                try {
                    return Files.readString(includeFile.toPath(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    LOGGER.warn("读取 include 文件失败: {}", includeFile, e);
                    return null;
                }
            }
            return null;
        };
    }

    /** 保存预处理后的源码到源目录 .shader-debug/（调试用） */
    private void savePreprocessedSource(File sourceFile, String passName, String stage, String source) {
        try {
            File debugDir = new File(sourceFile.getParent(), ".shader-debug");
            Files.createDirectories(debugDir.toPath());
            String baseName = sourceFile.getName().replace(".shader", "");
            File outFile = new File(debugDir, baseName + "_" + passName + "_" + stage + ".glsl");
            Files.writeString(outFile.toPath(), source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("保存预处理源码失败", e);
        }
    }

    /** ShaderImportSettings → ShaderCompiler.Options（设置真正传入编译器） */
    private ShaderCompiler.Options toCompileOptions(ShaderImportSettings settings) {
        return new ShaderCompiler.Options()
            .generateDebugInfo(settings.isGenerateDebugInfo())
            .optimization(switch (settings.getOptimizationLevel()) {
                case NONE -> ShaderCompiler.Optimization.NONE;
                // shaderc 仅 zero/size/performance 三档，STANDARD 与 MAXIMUM 同为 performance
                case STANDARD, MAXIMUM -> ShaderCompiler.Optimization.PERFORMANCE;
            })
            .targetVulkanVersion(settings.getTargetVulkanVersion());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  类型映射
    // ═══════════════════════════════════════════════════════════════════════

    private ShaderPropertyType mapPropertyType(String type) {
        return switch (type) {
            case "Float" -> ShaderPropertyType.FLOAT;
            case "Range" -> ShaderPropertyType.RANGE;
            case "Int" -> ShaderPropertyType.INT;
            case "Bool" -> ShaderPropertyType.BOOL;
            case "Vector2" -> ShaderPropertyType.VECTOR2;
            case "Vector3" -> ShaderPropertyType.VECTOR3;
            case "Vector4" -> ShaderPropertyType.VECTOR4;
            case "Color" -> ShaderPropertyType.COLOR;
            case "Texture2D" -> ShaderPropertyType.TEXTURE;
            default -> null;
        };
    }
}
