package cn.donting.javelin.editor.inspector;

import cn.donting.javelin.core.inspector.InsHeader;
import cn.donting.javelin.core.reflective.ReflectiveFactory;
import cn.donting.javelin.core.scene.Component;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.editor.plugin.EditorLang;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「添加组件」候选清单收集（Unity Add Component 口径）。
 *
 * <p>候选面 = {@link ReflectiveFactory} 已注册类中「Component 子类 + 非抽象 + 贴
 * {@code @InsHeader}」者——闸门与 Inspector 组件分组同源（不进检查器的组件列出
 * 来也看不见字段）；Transform 剔除（Node 构造自动持有，不可重复添加）。零反射
 * 枚举注册表：插件/游戏自定义组件只要注册进工厂且贴注即自动入列，无需在此登记。</p>
 *
 * <p>唯一性预判（{@code @UniqueComponent} 两级）——本节点已有=节点域；本场景已有=
 * 场景域（{@link GameScene#hasSceneComponent}）。这里只是列表灰显，落表拒绝仍由
 * {@link Node#addComponent} 兜（选中期间场景变化的竞态）。</p>
 *
 * <p>线程约束：须在引擎主线程执行（读节点组件表与场景注册表），经
 * {@code JavelinEditor.submitTask} 帧边界提交。</p>
 *
 * @author javelin
 * @create 2026/11/8
 */
public final class AddComponentCatalog {

    private AddComponentCatalog() {
        // 静态工具类禁止实例化
    }

    /**
     * 收集候选清单（按显示名排序）。
     *
     * @param node 目标节点（须已归属用户场景；归属校验归调用方）
     * @return 候选列表（含不可添加项，{@link AddComponentVo#addable} 区分）
     */
    public static List<AddComponentVo> collect(Node node) {
        List<AddComponentVo> candidates = new ArrayList<>();
        for (Class<?> type : ReflectiveFactory.registeredClasses()) {
            if (!Component.class.isAssignableFrom(type)
                    || type == Transform.class
                    || Modifier.isAbstract(type.getModifiers())
                    || !type.isAnnotationPresent(InsHeader.class)) {
                continue;
            }
            candidates.add(toVo(node, type.asSubclass(Component.class)));
        }
        candidates.sort(Comparator.comparing((AddComponentVo vo) -> vo.name)
                .thenComparing(vo -> vo.type.getName()));
        return candidates;
    }

    /**
     * 单条候选（显示名/图标解析与 InspectorReflector.componentLabel 同式，
     * {@code @InsHeader} 头元数据扩容时两处一并抽取）。
     */
    private static AddComponentVo toVo(Node node, Class<? extends Component> type) {
        AddComponentVo vo = new AddComponentVo();
        vo.type = type;
        InsHeader header = type.getAnnotation(InsHeader.class);
        vo.name = header != null && !header.label().isBlank()
                ? EditorLang.text(header.label()) : type.getSimpleName();
        vo.icon = header != null ? header.icon() : "";
        vo.addable = true;
        if (Component.isUniqueComponent(type) && node.getComponent(type) != null) {
            vo.addable = false;
            vo.reason = EditorLang.text("inspector.addComponent.exists");
        } else if (Component.isSceneUniqueComponent(type)
                && node.getScene() instanceof GameScene scene && scene.hasSceneComponent(type)) {
            vo.addable = false;
            vo.reason = EditorLang.text("inspector.addComponent.sceneExists");
        }
        return vo;
    }
}
