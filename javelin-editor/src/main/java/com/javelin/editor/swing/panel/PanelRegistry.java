package com.javelin.editor.swing.panel;

import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面板注册表（Editor_Swing_Design §5.2——扩展点，对齐菜单域先例：集中显式
 * 注册不做注解扫描，{@code EditorPanelSetup} 启动期装配）。
 *
 * <p>插件预留（本重构核心收益落点）：第三方 jar 注册描述符 + MenuRegistry
 * 「窗口」域菜单项（M5）即可把面板贡献进编辑器——与插件贡献菜单命令同一
 * 模式。id 重复抛异常 fail-fast；注册序即「窗口」菜单显示序（LinkedHashMap）。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public final class PanelRegistry {

    private static final Map<String, PanelDescriptor> PANELS = new LinkedHashMap<>();

    private PanelRegistry() {
        // 静态门面禁止实例化
    }

    /**
     * 注册面板。
     *
     * @param descriptor 面板描述符
     * @throws IllegalStateException 面板 id 重复
     */
    public static synchronized void register(PanelDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor 不能为 null");
        }
        if (PANELS.containsKey(descriptor.getId())) {
            throw new IllegalStateException("重复面板 id: " + descriptor.getId());
        }
        PANELS.put(descriptor.getId(), descriptor);
    }

    /**
     * 按 id 查面板描述符。
     *
     * @param id 面板 id
     * @return 描述符，未注册返回 null
     */
    public static synchronized PanelDescriptor get(String id) {
        return id == null ? null : PANELS.get(id);
    }

    /** 全部面板（注册序拷贝；M5「窗口」域菜单数据源） */
    public static synchronized List<PanelDescriptor> all() {
        return new ArrayList<>(PANELS.values());
    }

    /**
     * 造面板组件（factory 首调——实例缓存归 DockRoot，本方法幂等性不保证）。
     *
     * @param id 面板 id
     * @return 面板组件
     * @throws IllegalArgumentException 面板未注册
     */
    public static JComponent create(String id) {
        PanelDescriptor descriptor = get(id);
        if (descriptor == null) {
            throw new IllegalArgumentException("面板未注册: " + id);
        }
        return descriptor.createComponent();
    }
}
