package cn.donting.javelin.editor.importer.compute;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.editor.importer.ImportSettings;

/**
 * .compute 导入设置。
 *
 * <p>当前无额外配置项（一文件一 kernel，入口恒 main）。
 * {@link #getType()} = "ComputeShader" 与 MetaManager.detectAssetType 的 .compute 检测值一致。</p>
 */
@Reflective
public class ComputeShaderImportSettings extends ImportSettings {

    public ComputeShaderImportSettings() {
    }

    @Override
    public String getType() {
        return "ComputeShader";
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
