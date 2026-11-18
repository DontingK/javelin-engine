package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.core.render.AmbientMode;
import cn.donting.javelin.core.scene.Component;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.Environment;
import cn.donting.javelin.editor.inspector.ComponentInspectorVo;
import cn.donting.javelin.editor.inspector.InspectorFieldVo;
import cn.donting.javelin.editor.inspector.InspectorMeta;
import cn.donting.javelin.editor.plugin.EditorLangTestBase;
import cn.donting.javelin.editor.swing.inspector.widget.ColorField;
import cn.donting.javelin.editor.swing.inspector.widget.JeSelect;
import org.junit.jupiter.api.Test;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 检查器行高回归（2026-11-16）：颜色行（{@link ColorField}，preferred 高 18）曾被压成 3px，
 * 用户实机表现为「色块变成一条横线」（相机 {@code backgroundColor} 与环境光
 * {@code AmbientSetting.color} 两处）。
 *
 * <p>根因在 {@link ComponentSection} 的组显隐重排：子组展开后祖先链的 cached pref 没被作废，
 * viewport 按「折叠态」的高度给格子（最近 validate root 实为 {@code JScrollPane}）；容器矮于
 * 最小尺寸时 {@code GridBagLayout} 走 MINSIZE 分支并把负偏移折进首行高度，颜色行
 * （JButton 子类，minimum 高≈0）被榨成一条线。</p>
 *
 * <p>本测试按 {@code NodeInspectorContent} 真实链路搭壳（{@link InsScrollablePanel} + BoxLayout +
 * JScrollPane）：既覆盖「新建即展开」的静态路径，也覆盖「切父枚举 → 子组展开」的运行态路径
 * （后者才是实机 bug 的现场）。第二轮追加两条「确定性兜底」用例：竖直 minimum = pref
 * （BoxLayout 压不动分组）+ 被压容器下颜色行仍为 18。
 */
public class InspectorRowHeightTest extends EditorLangTestBase {

    private static final int COLOR_ROW_HEIGHT = 18;

    /** 相机背景色：无 {@code @InsFieldGroup}，走静态路径 */
    @Test
    void cameraColorRowKeepsHeight() {
        ComponentSection section = sectionOf(new Camera(), "Camera");
        JScrollPane scroll = mount(section, 720);
        layoutAll(scroll);
        assertColorRowHeight(section, scroll, "Camera");
    }

    /** 环境光颜色：子组初始即展开（ambientMode 预置 CONSTANT） */
    @Test
    void environmentColorRowKeepsHeightWhenGroupOpen() {
        Environment environment = new Environment();
        environment.setAmbientMode(AmbientMode.CONSTANT);
        ComponentSection section = sectionOf(environment, "Environment");
        JScrollPane scroll = mount(section, 720);
        layoutAll(scroll);
        assertColorRowHeight(section, scroll, "Environment(预置展开)");
    }

    /** 环境光颜色：实机路径——先 OFF（组收起），再切 CONSTANT 展开 */
    @Test
    void environmentColorRowKeepsHeightAfterSwitchToConstant() {
        Environment environment = new Environment();
        ComponentSection section = sectionOf(environment, "Environment");
        JScrollPane scroll = mount(section, 720);
        layoutAll(scroll);

        switchToConstant(section);
        // 模拟绘制期：整棵子树由 validate root 统一校验（真实 AWT 事件循环做的事）
        scroll.validate();
        layoutAll(scroll);
        assertColorRowHeight(section, scroll, "Environment(切 CONSTANT)");
    }

    /** 视口比内容矮（组展开后内容溢出成滚动条）时，行高同样不能被压扁 */
    @Test
    void colorRowKeepsHeightWhenViewportIsShort() {
        Environment environment = new Environment();
        ComponentSection section = sectionOf(environment, "Environment");
        JScrollPane scroll = mount(section, 260);
        layoutAll(scroll);

        switchToConstant(section);
        scroll.validate();
        layoutAll(scroll);
        assertColorRowHeight(section, scroll, "Environment(短视口)");
    }

    /**
     * 容器被压到比 pref 矮（本轮修复的确定性兜底）：BoxLayout 只能给 section 首选高度，
     * 溢出的部分由滚动区承接，内层 GridBag 拿不到矮格子，颜色行保持 18。
     */
    @Test
    void colorRowKeepsHeightWhenParentBoxIsShorterThanPreferred() {
        ComponentSection section = sectionOf(new Camera(), "Camera");
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.PAGE_AXIS));
        box.add(section);
        box.setSize(360, 40);
        layoutAll(box);
        List<ColorField> colors = new ArrayList<>();
        collectColorFields(section, colors);
        if (colors.isEmpty()) {
            throw new AssertionError("未找到颜色控件：Camera");
        }
        StringBuilder diag = new StringBuilder("\nCamera(被压容器) 链路高度：\n");
        diag.append("  box ").append(size(box)).append(" pref=").append(pref(box)).append('\n');
        diag.append("  section ").append(size(section)).append(" pref=").append(pref(section))
                .append(" min=").append(minHeight(section)).append('\n');
        for (ColorField field : colors) {
            diag.append("  ColorField ").append(size(field)).append('\n');
            assertEquals(COLOR_ROW_HEIGHT, field.getHeight(), diag.toString());
        }
    }

    /** 最小尺寸兜底本身：色块与分组的竖直 minimum 都等于首选高度（BUG-10 不被压缩的前提） */
    @Test
    void minimumHeightEqualsPreferredForColorRowAndSection() {
        ColorField field = new ColorField("1,1,1,1", value -> {
        });
        assertEquals(COLOR_ROW_HEIGHT, field.getPreferredSize().height);
        assertEquals(field.getPreferredSize().height, field.getMinimumSize().height);

        ComponentSection section = sectionOf(new Camera(), "Camera");
        assertEquals(section.getPreferredSize().height, minHeight(section));
    }

    private static int minHeight(JComponent component) {
        return component.getMinimumSize().height;
    }

    private static void switchToConstant(ComponentSection section) {
        JeSelect select = firstSelect(section);
        if (select == null) {
            throw new AssertionError("未找到父枚举行 JeSelect");
        }
        for (int i = 0; i < select.getItemCount(); i++) {
            InspectorFieldVo.Option option = select.getItemAt(i);
            if ("CONSTANT".equals(option.value)) {
                select.setSelectedItem(option);
                return;
            }
        }
        throw new AssertionError("ambientMode 选项里没有 CONSTANT");
    }

    private void assertColorRowHeight(ComponentSection section, JScrollPane scroll, String label) {
        List<ColorField> colors = new ArrayList<>();
        collectColorFields(section, colors);
        if (colors.isEmpty()) {
            throw new AssertionError("未找到颜色控件：" + label);
        }
        StringBuilder diag = new StringBuilder("\n").append(label).append(" 链路高度：\n");
        diag.append("  scroll ").append(size(scroll)).append(" pref=").append(pref(scroll)).append('\n');
        for (JComponent child : componentsOf(section)) {
            diag.append("    ").append(child.getClass().getSimpleName()).append(' ')
                    .append(size(child)).append(" pref=").append(pref(child)).append('\n');
            for (JComponent grand : componentsOf((java.awt.Container) child)) {
                diag.append("      ").append(grand.getClass().getSimpleName()).append(' ')
                        .append(size(grand)).append(" pref=").append(pref(grand)).append('\n');
                for (JComponent row : componentsOf((java.awt.Container) grand)) {
                    diag.append("        ").append(row.getClass().getSimpleName()).append(' ')
                            .append(size(row)).append(" pref=").append(pref(row))
                            .append(row.isVisible() ? "" : " (hidden)").append('\n');
                }
            }
        }
        for (ColorField field : colors) {
            diag.append("  ColorField ").append(size(field)).append('\n');
            assertEquals(COLOR_ROW_HEIGHT, field.getHeight(), diag.toString());
        }
    }

    private static ComponentSection sectionOf(Component component, String label) {
        return new ComponentSection(null, voOf(component, label),
                (owner, host, field, type, value) -> {
                }, false, null);
    }

    private static JScrollPane mount(ComponentSection section, int height) {
        InsScrollablePanel content = new InsScrollablePanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.add(section);
        content.add(Box.createVerticalStrut(2));
        JScrollPane scroll = new JScrollPane(content);
        scroll.setSize(360, height);
        return scroll;
    }

    private static JeSelect firstSelect(java.awt.Container container) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof JeSelect select) {
                return select;
            }
            if (child instanceof java.awt.Container nested) {
                JeSelect found = firstSelect(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String size(java.awt.Component component) {
        return component.getWidth() + "x" + component.getHeight()
                + "@" + component.getX() + "," + component.getY();
    }

    private static String pref(java.awt.Component component) {
        Dimension preferred = component.getPreferredSize();
        return preferred.width + "x" + preferred.height;
    }

    private static List<JComponent> componentsOf(java.awt.Container container) {
        List<JComponent> list = new ArrayList<>();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof JComponent jc) {
                list.add(jc);
            }
        }
        return list;
    }

    private static void collectColorFields(java.awt.Container container, List<ColorField> out) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof ColorField field) {
                out.add(field);
            }
            if (child instanceof java.awt.Container nested) {
                collectColorFields(nested, out);
            }
        }
    }

    private static void layoutAll(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) {
                layoutAll(nested);
            }
        }
    }

    static ComponentInspectorVo voOf(Component component, String name) {
        ComponentInspectorVo vo = new ComponentInspectorVo();
        vo.component = component;
        vo.name = name;
        vo.enabled = true;
        vo.required = false;
        vo.fields = new ArrayList<>();
        List<String> groupSeen = new ArrayList<>();
        for (InspectorMeta.PropMeta prop : InspectorMeta.propsOf(component.getClass())) {
            if ("enabled".equals(prop.fieldName)) {
                continue;
            }
            InspectorFieldVo fieldVo = InspectorMeta.fieldVo(component, prop);
            if (fieldVo.parentField != null && !groupSeen.contains(fieldVo.parentField)) {
                groupSeen.add(fieldVo.parentField);
            } else if (fieldVo.parentField != null) {
                fieldVo.groupLabel = null;
            }
            vo.fields.add(fieldVo);
        }
        return vo;
    }
}
