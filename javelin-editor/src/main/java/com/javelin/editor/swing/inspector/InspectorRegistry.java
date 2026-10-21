package com.javelin.editor.swing.inspector;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 检查器注册表（静态门面，对齐菜单/面板/文件类型域先例）：启动期
 * {@link InspectorSetup} 集中显式注册，不做注解扫描、不做动态增删；键 =
 * {@link InspectorProvider#NODE_KEY}（node 分轨）或扩展名（含点号小写），同键
 * 重复注册抛 IllegalStateException fail-fast。统一接管 Node 与资产文件
 * （2026-10-21 拍板——外壳零特例，Unity 多态 Inspector 口径）。
 *
 * @author javelin
 * @create 2026/10/21
 */
public final class InspectorRegistry {

    private static final Map<String, InspectorProvider> PROVIDERS = new LinkedHashMap<>();

    private InspectorRegistry() {
    }

    /**
     * 注册（保持注册序；键非 node 键须为含点号小写扩展名，同键已注册抛
     * IllegalStateException）。
     */
    public static synchronized void register(InspectorProvider provider) {
        for (String key : provider.keys()) {
            if (!InspectorProvider.NODE_KEY.equals(key)
                    && (!key.startsWith(".") || !key.equals(key.toLowerCase(Locale.ROOT)))) {
                throw new IllegalArgumentException("检查器键须为 node 键或含点号小写扩展名: " + key);
            }
            InspectorProvider prev = PROVIDERS.putIfAbsent(key, provider);
            if (prev != null) {
                throw new IllegalStateException("检查器键重复注册: " + key);
            }
        }
    }

    /**
     * 按选中快照查 provider：节点选中走 node 键；文件选中按扩展名 endsWith 匹配
     * （忽略大小写，FileTypeRegistry.find 口径）；目录/无选中/无接管返回 null
     * （外壳显示「无检查器」或空态）。
     */
    public static synchronized InspectorProvider find(InspectorSelection selection) {
        if (selection.node() != null) {
            return PROVIDERS.get(InspectorProvider.NODE_KEY);
        }
        File file = selection.file();
        if (file == null || !file.isFile()) {
            return null;
        }
        String lower = file.getName().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, InspectorProvider> entry : PROVIDERS.entrySet()) {
            // 扩展名分轨（点号键）——node 键不参与文件名 endsWith 误配
            if (entry.getKey().startsWith(".") && lower.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
