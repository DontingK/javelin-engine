package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.editor.inspector.ComponentInspectorVo;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.swing.SvgIcon;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.util.function.Supplier;

/**
 * 组件头右键菜单（检查器组件分组头，Unity 组件头菜单口径）。
 *
 * <p>构建即读 {@code @InsHeader} 显示名等元数据，与 {@link ComponentSection} 同源；
 * 删除项 required 组件（Transform）置灰——core {@code Node.removeComponent} 同判。</p>
 *
 * @author javelin
 * @create 2026/11/8
 */
public final class ComponentHeaderMenus {

    private ComponentHeaderMenus() {
    }

    /**
     * 组件头菜单：{@code @InsHeader} 显示名图标 + 删除项（required 置灰）。
     *
     * @param vo      组件分组 VO
     * @param onRemove 删除项动作（宿主接过；null = 仅展示不可点）
     */
    public static Supplier<JPopupMenu> menuSupplier(ComponentInspectorVo vo, Runnable onRemove) {
        return () -> {
            JPopupMenu popup = new JPopupMenu();
            // 删除项图标（delete.svg；资源缺失不占位——菜单域同口径）
            SvgIcon deleteIcon = SvgIcon.of("delete", 16);
            JMenuItem remove = new JMenuItem(EditorLang.text("inspector.removeComponent"),
                    deleteIcon.isAvailable() ? deleteIcon : null);
            remove.setEnabled(!vo.required && onRemove != null);
            if (onRemove != null) {
                remove.addActionListener(e -> onRemove.run());
            }
            popup.add(remove);
            return popup;
        };
    }
}
