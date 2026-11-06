package cn.donting.javelin.editor.importer.cubemap;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.editor.importer.ImportSettings;

/**
 * cubemap 导入设置。当前版本无额外配置项（faceSize 固定 512 拍板 2026-11-03，
 * 可调后置）。{@link #getType()} = "Cubemap" 与 MetaManager/
 * AssetImportPipeline 的 detectAssetType 检测值一致。
 */
@Reflective
public class CubemapImportSettings extends ImportSettings {

    public CubemapImportSettings() {
    }

    @Override
    public String getType() {
        return "Cubemap";
    }

    @Override
    public boolean validate() {
        return true;
    }

    @Override
    public void write(OutputCapsule output) {
        // 无额外字段
    }

    @Override
    public void read(InputCapsule input) {
        // 无额外字段
    }
}
