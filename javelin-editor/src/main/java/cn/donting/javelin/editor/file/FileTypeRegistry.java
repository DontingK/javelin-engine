package cn.donting.javelin.editor.file;

import cn.donting.javelin.editor.plugin.EditorLang;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 文件类型注册表（静态门面，对齐菜单域先例 MenuRegistry/PanelRegistry）：
 * 启动期 {@link FileTypeSetup} 集中显式注册，不做注解扫描、不做动态增删；
 * 重复扩展名注册抛 IllegalStateException fail-fast。
 *
 * <p>消费方：ProjectPanel 条目图标/双击打开、project.open 右键命令。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public final class FileTypeRegistry {

    private static final Map<String, FileType> TYPES = new LinkedHashMap<>();

    private FileTypeRegistry() {
    }

    /**
     * 注册（扩展名小写入表，保持注册序；同扩展名已注册抛 IllegalStateException）。
     */
    public static synchronized void register(FileType type) {
        for (String ext : type.extensions()) {
            String key = ext.toLowerCase(Locale.ROOT);
            FileType prev = TYPES.putIfAbsent(key, type);
            if (prev != null) {
                throw new IllegalStateException(EditorLang.text("fileType.duplicateExt", key));
            }
        }
    }

    /**
     * 按文件名查类型（扩展名 endsWith 匹配、忽略大小写；无注册返回 null）。
     */
    public static synchronized FileType find(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, FileType> entry : TYPES.entrySet()) {
            if (lower.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 按文件查类型（null / 不存在的文件返回 null）。
     */
    public static FileType of(File file) {
        return file != null ? find(file.getName()) : null;
    }
}
