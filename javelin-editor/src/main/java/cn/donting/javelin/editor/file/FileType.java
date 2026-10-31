package cn.donting.javelin.editor.file;

import java.io.File;
import java.util.List;

/**
 * 文件类型（项目面板口径）：注册进 {@link FileTypeRegistry}，提供条目图标与
 * 打开操作——双击条目 / project.open 右键命令统一分发到本接口。
 *
 * <p>对齐 MenuCommand「类实现接口」先例：各类型独立类、启动期
 * {@link FileTypeSetup} 集中显式注册，不做注解扫描。扩展名含点号小写
 * （[{@code ".scene"}]，对齐 AssetImporter.getSupportedExtensions 口径）。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public interface FileType {

    /**
     * 支持的扩展名（含点号小写，如 {@code [".scene"]}）。
     */
    List<String> extensions();

    /**
     * 图标名（classpath /icon/{name}.svg 裸名，菜单域口径）。
     */
    String icon();

    /**
     * 是否可打开（false = 条目双击无动作、project.open 置灰——如 .java/.json 纯图标型）。
     */
    default boolean canOpen() {
        return false;
    }

    /**
     * 打开。EDT 调用；内部自管线程——场景打开走 {@code JavelinEditor.submitTask}
     * 帧边界（与渲染 pull 同帧序，同命令域口径）。
     */
    default void open(File file) {
    }
}
