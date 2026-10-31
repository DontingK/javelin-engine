package cn.donting.javelin.editor.event;

import cn.donting.javelin.editor.appstate.TransformWatchAppState;
import cn.donting.javelin.core.scene.Node;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;

/**
 * 节点 Transform 变换事件（scene.nodeTransformChanged）：节点 local TRS 实际
 * 变化后发布（结构/属性事件分类的第三类：结构 → {@link SceneChangedEvent}
 * 重拉整树、改名 → {@link NodeRenamedEvent} 定点刷 label、变换 → 本事件定点
 * 刷数值）。
 *
 * <p>发布方 = {@link TransformWatchAppState} 帧级
 * 快照 diff（用户拍板 2026-10-06，<b>检测范围 = 当前选中节点</b>——消费方就是
 * Inspector 的选中面板）——写入方（gizmo 拖拽/将来 Inspector 回写/运行时脚本）
 * 零感知，变更自动发现零漏报，与 SceneWatchAppState「替代写入方显式发通知」
 * 同机制（作用域收窄到选中节点）。消费方：Inspector 拖拽期间实时刷新
 * （Unity 口径）；检出的变更同时置场景脏标记。</p>
 *
 * <p>载荷 = Node 引用（2026-10-18 全链引用化拍板，原 id 值传递废弃）+ 变更后
 * 完整新值 TRS（消费方免拉取）——position [x,y,z]、rotation 四元数 [x,y,z,w]、
 * scale [x,y,z]（纯数据快照，事件构造时拷贝）。发布方在引擎线程，Swing 消费方
 * 自行 EDT hop（Editor_Swing_Design §7.3）。</p>
 *
 * @author javelin
 * @create 2026/10/6
 */
public final class NodeTransformChangeEvent implements EditorEvent {

    /** 变更节点 */
    private final Node node;
    /** 变更后 local 位置 [x,y,z] */
    private final float[] position;
    /** 变更后 local 旋转四元数 [x,y,z,w] */
    private final float[] rotation;
    /** 变更后 local 缩放 [x,y,z] */
    private final float[] scale;

    /**
     * @param node     变更节点
     * @param position 变更后 local 位置（构造时拷贝值，事件为纯数据快照）
     * @param rotation 变更后 local 旋转四元数（构造时拷贝值）
     * @param scale    变更后 local 缩放（构造时拷贝值）
     */
    public NodeTransformChangeEvent(Node node, Vector3fc position, Quaternionfc rotation, Vector3fc scale) {
        this.node = node;
        this.position = new float[]{position.x(), position.y(), position.z()};
        this.rotation = new float[]{rotation.x(), rotation.y(), rotation.z(), rotation.w()};
        this.scale = new float[]{scale.x(), scale.y(), scale.z()};
    }

    /** 变更节点 */
    public Node getNode() {
        return node;
    }

    /** 变更后 local 位置 [x,y,z] */
    public float[] getPosition() {
        return position;
    }

    /** 变更后 local 旋转四元数 [x,y,z,w] */
    public float[] getRotation() {
        return rotation;
    }

    /** 变更后 local 缩放 [x,y,z] */
    public float[] getScale() {
        return scale;
    }
}
