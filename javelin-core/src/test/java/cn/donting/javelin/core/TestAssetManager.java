package cn.donting.javelin.core;

import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.assets.AssetLibManager;
import cn.donting.javelin.core.assets.font.BitmapFont;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.tool.debugtext.DebugTextFontRasterizer;
import cn.donting.javelin.tool.shaderc.ShaderStage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试侧资产管理器：core 测试/Demo 无编辑器导入链，内置着色器资产（SpvShader）
 * 与点阵字库资产（BitmapFont）Library miss 时按 classpath 内置源
 * （builtin-asset-core，随 javelin-core 主资源进 runtimeClasspath）现生成返回
 * ——SpvShader 用 tool shaderc 现编译、BitmapFont 用 tool 光栅化器 + 打包字体现烘焙；
 * ShadowPass/SkyboxPass/PickPass/DebugDrawTextSubsystem 等覆盖制 Pass 与子系统的
 * 装载闭环（2026-11-02 拍板：测试类自行编译加载，用户方向；2026-11-07 随 DebugText
 * 迁内置资产补字库面）。其余行为同 {@link AssetLibManager}（./Library）。
 */
public class TestAssetManager extends AssetLibManager {

    /** "core/ 前缀"（释放后资产路径前缀）对应 classpath 的内置资产目录 */
    private static final String BUILTIN_RESOURCE_PREFIX = "builtin-asset-core/";
    private static final String ASSET_PATH_PREFIX = "core/";

    /** 现编译缓存（path → SpvShader；覆盖制 Pass 启动期按路径一次装载） */
    private final Map<String, SpvShader> spvCache = new ConcurrentHashMap<>();
    /** 现烘焙缓存（path → BitmapFont；字库烘焙较重，一次装载后复用） */
    private final Map<String, BitmapFont> fontCache = new ConcurrentHashMap<>();

    public TestAssetManager(File libraryDir) {
        super(libraryDir);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Asset> T loadAsset(String path, Class<T> assetClass) {
        T asset = super.loadAsset(path, assetClass);
        if (asset != null) {
            return asset;
        }
        if (assetClass == SpvShader.class) {
            return (T) builtinSpvOrFallback(path);
        }
        if (assetClass == BitmapFont.class) {
            return (T) builtinBitmapFontOrFallback(path);
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Asset> T loadAssetInstance(String path, Class<T> assetClass) {
        T asset = super.loadAssetInstance(path, assetClass);
        if (asset != null) {
            return asset;
        }
        if (assetClass == SpvShader.class) {
            return (T) builtinSpvOrFallback(path);
        }
        if (assetClass == BitmapFont.class) {
            return (T) builtinBitmapFontOrFallback(path);
        }
        return null;
    }

    /**
     * 内置 SpvShader 现编译：仅接管 core/ 前缀的内置资产路径（编辑器管辖之外、
     * 引擎覆盖制 Pass 的自有着色器）；其余路径维持 null（上层显性报错，红线 15）。
     */
    private SpvShader builtinSpvOrFallback(String path) {
        if (path == null || !path.startsWith(ASSET_PATH_PREFIX)) {
            return null;
        }
        return spvCache.computeIfAbsent(path, p -> {
            String resource = BUILTIN_RESOURCE_PREFIX + p.substring(ASSET_PATH_PREFIX.length());
            String lower = p.toLowerCase(Locale.ROOT);
            ShaderStage stage;
            if (lower.endsWith(".vert")) {
                stage = ShaderStage.VERTEX;
            } else if (lower.endsWith(".frag")) {
                stage = ShaderStage.FRAGMENT;
            } else {
                throw new IllegalStateException("无法从扩展名推断着色器 stage: " + p
                        + "（SpvShader 资产一文件一 stage）");
            }
            byte[] spv = TestShaders.compileResource(resource, stage);
            String name = p.substring(p.lastIndexOf('/') + 1);
            return new SpvShader(name, spv);
        });
    }

    /**
     * 内置 BitmapFont 现烘焙：{@code .chars} 字符集文本 → tool 光栅化器 + 打包字体
     * → JDTF 字节。仅接管 {@code core/} 前缀的内置资产路径；其余路径维持 null
     * （上层显性报错，红线 15）。烘焙较重（4500+ 字形），按路径缓存复用。
     */
    private BitmapFont builtinBitmapFontOrFallback(String path) {
        if (path == null || !path.startsWith(ASSET_PATH_PREFIX)) {
            return null;
        }
        return fontCache.computeIfAbsent(path, p -> {
            String resource = BUILTIN_RESOURCE_PREFIX + p.substring(ASSET_PATH_PREFIX.length());
            try (InputStream in = TestAssetManager.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("内置字库源不存在: " + resource);
                }
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                SortedSet<Integer> codepoints = DebugTextFontRasterizer.parseCharset(text);
                byte[] data = DebugTextFontRasterizer.rasterizeToBytes(
                        DebugTextFontRasterizer.bundledFontPath(), codepoints);
                String name = p.substring(p.lastIndexOf('/') + 1);
                return new BitmapFont(name, data);
            } catch (IOException e) {
                throw new IllegalStateException("内置字库现烘焙失败: " + path, e);
            }
        });
    }
}
