package cn.donting.javelin.editor.importer;

import cn.donting.javelin.core.assets.AbsAsset;
import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.reflective.ReflectiveFactory;
import cn.donting.javelin.editor.meta.MetaManager;
import cn.donting.javelin.editor.plugin.EditorLang;

import java.util.List;

/**
 * 导入期壳资产管理器：loadGuid 不加载 Library 产物，按声明类型返回带 guid 的壳实例
 * ——导入产物只写引用 guid（BinaryOutputCapsule 对 Asset 引用只写 TYPE_ASSET+guid），
 * 与依赖产物是否已生成解耦（首启多 worker 竞态根除）。引用目标按
 * {@link MetaManager#getMetaByGuid(String)} 判定，未登记返回 null（与真实 loadGuid
 * 的 miss 同语义）；导入只走 loadGuid，其余接口面显性抛错。
 */
public class ShellAssetManager implements AssetManager {

    private final MetaManager metaManager;

    public ShellAssetManager(MetaManager metaManager) {
        this.metaManager = metaManager;
    }

    @Override
    public <T extends Asset> T loadGuid(String guid, Class<T> assetClass) {
        if (metaManager.getMetaByGuid(guid) == null) {
            return null;
        }
        T shell = ReflectiveFactory.newInstance(assetClass);
        ((AbsAsset) shell).setGuid(guid);
        return shell;
    }

    @Override
    public <T extends Asset> T loadGuidInstance(String guid, Class<T> assetClass) {
        throw unsupported("loadGuidInstance");
    }

    @Override
    public <T extends Asset> T loadAsset(String path, Class<T> assetClass) {
        throw unsupported("loadAsset");
    }

    @Override
    public <T extends Asset> T loadAssetInstance(String path, Class<T> assetClass) {
        throw unsupported("loadAssetInstance");
    }

    @Override
    public List<String> listAssetPaths(String suffix) {
        throw unsupported("listAssetPaths");
    }

    @Override
    public String pathOfGuid(String guid) {
        throw unsupported("pathOfGuid");
    }

    @Override
    public String guidOfPath(String path) {
        throw unsupported("guidOfPath");
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(EditorLang.text("text.shellUnsupportedMethod", method));
    }
}
