package cn.donting.javelin.editor.importer.mesh;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.editor.importer.ImportSettings;

/**
 * 简单网格导入设置。
 *
 * <p>控制 .smesh 文件的导入行为，当前版本无额外配置项。</p>
 */
@Reflective
public class SimpleMeshImportSettings extends ImportSettings {

    public SimpleMeshImportSettings() {
    }

    @Override
    public String getType() {
        return "SimpleMesh";
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
