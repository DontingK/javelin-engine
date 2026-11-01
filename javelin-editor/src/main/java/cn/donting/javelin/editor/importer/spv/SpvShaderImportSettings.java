package cn.donting.javelin.editor.importer.spv;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.editor.importer.ImportSettings;

/**
 * .vert/.frag 着色器导入设置。
 *
 * <p>当前无额外配置项（扩展名即 stage）。
 * {@link #getType()} = "SpvShader" 与 AssetMeta 的 assetType 值一致。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class SpvShaderImportSettings extends ImportSettings {

    public SpvShaderImportSettings() {
    }

    @Override
    public String getType() {
        return "SpvShader";
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
