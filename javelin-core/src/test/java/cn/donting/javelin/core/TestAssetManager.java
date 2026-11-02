package cn.donting.javelin.core;

import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.assets.AssetLibManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.tool.shaderc.ShaderStage;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试侧资产管理器：core 测试/Demo 无编辑器导入链，内置着色器资产（SpvShader）
 * Library miss 时按 classpath 内置源（builtin-asset-core，经 javelin-editor 测试
 * 依赖进 runtimeClasspath）用 tool shaderc 现编译返回——ShadowPass/SkyboxPass 等
 * 覆盖制 Pass 在 runTest 环境的装载闭环（2026-11-02 拍板：测试类自行编译加载，
 * 用户方向）。其余行为同 {@link AssetLibManager}（./Library）。
 */
public class TestAssetManager extends AssetLibManager {

    /** "core/ 前缀"（释放后资产路径前缀）对应 classpath 的内置资产目录 */
    private static final String BUILTIN_RESOURCE_PREFIX = "builtin-asset-core/";
    private static final String ASSET_PATH_PREFIX = "core/";

    /** 现编译缓存（path → SpvShader；覆盖制 Pass 启动期按路径一次装载） */
    private final Map<String, SpvShader> spvCache = new ConcurrentHashMap<>();

    public TestAssetManager(File libraryDir) {
        super(libraryDir);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Asset> T loadAsset(String path, Class<T> assetClass) {
        T asset = super.loadAsset(path, assetClass);
        if (asset == null && assetClass == SpvShader.class) {
            return (T) builtinSpvOrFallback(path);
        }
        return asset;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Asset> T loadAssetInstance(String path, Class<T> assetClass) {
        T asset = super.loadAssetInstance(path, assetClass);
        if (asset == null && assetClass == SpvShader.class) {
            return (T) builtinSpvOrFallback(path);
        }
        return asset;
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
}
