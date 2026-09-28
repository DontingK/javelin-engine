package com.javelin.editor.appstate;

import com.javelin.core.JavelinEngine;
import com.javelin.core.appstate.AppStateManager;
import com.javelin.core.input.Input;
import com.javelin.core.input.KeyCode;
import com.javelin.core.input.MouseButton;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.Transform;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 编辑器相机控制 AppState（Unity Scene 视图导航口径，2026-09-27 用户需求）。
 *
 * <p><b>操作（对齐 Unity 默认）：</b></p>
 * <ul>
 *   <li><b>WASDQE 常驻飞行</b>（2026-09-27 用户拍板：无需按住右键，直接响应——
 *       Unity 原始口径 WASD 须右键按住才飞，此处改防误触让位于直觉）：W/S 沿视向
 *       （含俯仰）、A/D 沿屏幕右向（水平）、Q/E 沿世界 Y 下/上，Shift = 3 倍速；</li>
 *   <li><b>按住右键</b>：鼠标移动 = 环视；</li>
 *   <li><b>按住中键拖拽</b>：平移（画面内容跟手）；</li>
 *   <li><b>滚轮</b>：沿视向推拉，步长随环绕焦点距离缩放（每格 ≈ 10%）；</li>
 *   <li><b>Alt+左键拖拽</b>：绕环绕焦点转动（焦点 = pos + forward·focusDistance，
 *       初始取相机到场景原点距离——空场景环绕网格中心）；</li>
 *   <li><b>Alt+右键拖拽</b>：沿视向推拉（上下拖，倍率随焦点距离）。</li>
 * </ul>
 *
 * <p>Unity 的 <b>F（聚焦选中物）</b> 需要选中/拾取系统，留待二期 Ray + Gizmo 选中
 * 再补（届时环绕焦点一并切到选中物包围盒中心）。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>yaw/pitch 是<b>自持状态</b>（弧度），旋转写回
 *       {@code rotationY(yaw).rotateX(pitch)}——不累计四元数（无飘移），
 *       pitch 正 = 低头（引擎先例 rotationX(45) 斜俯视，绕 X 正向 = 低头）；</li>
 *   <li>前向/右向用解析式（yaw-pitch 无 roll，水平右恒水平），
 *       初值从既有位姿经 {@code localToWorld.transformDirection} 正向提取
 *       ——JOML normalizedPositive 提轴族在此场景禁用（2026-09-26 大坑结案）；</li>
 *   <li>编辑器相机节点挂在场景根级（local == world），位姿写回
 *       {@code setLocalPosition/setLocalRotation} 即世界位姿；</li>
 *   <li>相机经 {@link com.javelin.editor.JavelinEditor#getEditorCamera()}
 *       （onInitialize 装配的编辑器相机实例）；场景未就绪时为 null，
 *       update 安全跳过。</li>
 * </ul>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class EditorCameraAppState extends AbsEditorAppState {

    // ---------------- 操作参数（经验初值，待实测调） ----------------

    /** 基础飞行速度（m/s，Shift 时 ×3） */
    private static final float MOVE_SPEED = 10.0f;

    /** Shift 加速倍率（Unity 同款） */
    private static final float SHIFT_MULTIPLIER = 3.0f;

    /** 环视/环绕灵敏度（弧度/像素，≈0.14°/px） */
    private static final float LOOK_SPEED = 0.0025f;

    /** 平移速率（m/px，× 焦点距离缩放） */
    private static final float PAN_SPEED = 0.0018f;

    /** 滚轮每格推拉比例（占焦点距离） */
    private static final float SCROLL_STEP = 0.1f;

    /** Alt+右键推拉速率（焦点距离/像素） */
    private static final float ALT_ZOOM_PX_FACTOR = 0.004f;

    /** 俯仰钳制（弧度，±89.9°——避免 ±90° 时 forward 退化） */
    private static final float PITCH_CLAMP = 1.56886f;

    /** 焦点距离下限（推拉不穿透） */
    private static final float MIN_FOCUS_DISTANCE = 0.5f;

    // ---------------- 位姿状态（自持 yaw/pitch，旋转不累计） ----------------

    /** 水平角（弧度，绕世界 Y；0 = 朝 +Z） */
    private float yaw;

    /** 俯仰角（弧度；正 = 低头，同引擎绕 X 正向约定） */
    private float pitch;

    /** 环绕焦点距离（滚轮推拉时同步缩放，环绕因此越拉近转得越小） */
    private float focusDistance = 30.0f;

    /** 首次 update 从编辑器相机既有位姿提取 yaw/pitch + 焦点距离 */
    private boolean poseInitialized;

    // ---------------- 热路径复用对象（update 零分配） ----------------

    private final Vector3f position = new Vector3f();
    private final Vector3f forward = new Vector3f();
    private final Vector3f right = new Vector3f();
    private final Vector3f worldUp = new Vector3f(0.0f, 1.0f, 0.0f);
    private final Vector3f scratch = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        super.onAttach(stateManager, engine);
        // 相机/场景就绪与否 update 每帧重查安全跳过（onInitialize 相机装配与本状态
        // 挂载同帧，挂载队列到下帧边界才 onAttach——顺序上无依赖）
    }

    @Override
    public void update(float deltaTime) {
        Camera camera = javelinEditor.getEditorCamera();
        if (camera == null) {
            return;
        }
        Transform transform = camera.getNode().getTransform();
        if (!poseInitialized) {
            initPose(transform);
        }
        transform.getWorldPosition(position);
        boolean poseChanged = false;

        boolean alt = Input.isDown(KeyCode.LEFT_ALT);

        // --- 右键：Alt+右键 = 沿视向推拉；纯右键 = 环视 ---
        if (Input.isMouseDown(MouseButton.RIGHT)) {
            if (alt) {
                poseChanged |= dolly(Input.mouseDeltaY() * -ALT_ZOOM_PX_FACTOR * focusDistance);
            } else {
                float dx = Input.mouseDeltaX();
                float dy = Input.mouseDeltaY();
                if (dx != 0.0f || dy != 0.0f) {
                    yaw += dx * LOOK_SPEED;
                    pitch = clampPitch(pitch + dy * LOOK_SPEED);
                    poseChanged = true;
                }
            }
        }

        // --- WASDQE 常驻飞行（无需右键，用户拍板 2026-09-27） ---
        poseChanged |= fly(deltaTime);

        // --- Alt+左键：绕焦点环绕 ---
        if (alt && Input.isMouseDown(MouseButton.LEFT)) {
            poseChanged |= orbit();
        }

        // --- 中键：平移（画面内容跟手） ---
        if (Input.isMouseDown(MouseButton.MIDDLE)) {
            poseChanged |= pan();
        }

        // --- 滚轮：沿视向推拉（步长随焦点距离） ---
        if (Input.scrollDeltaY() != 0.0f) {
            poseChanged |= dolly(Input.scrollDeltaY() * SCROLL_STEP * focusDistance);
        }

        if (poseChanged) {
            writeBack(transform);
        }
    }

    // ---------------- 操作实现 ----------------

    /**
     * WASDQE 飞行（常驻——2026-09-27 用户拍板无需右键按住）：W/S 沿视向（含俯仰）、
     * A/D 沿水平右向、Q/E 沿世界 Y 下/上，Shift ×3。各键独立叠加不归一（Unity 同款）。
     */
    private boolean fly(float deltaTime) {
        boolean moved = false;
        float speed = MOVE_SPEED * (Input.isDown(KeyCode.LEFT_SHIFT) ? SHIFT_MULTIPLIER : 1.0f) * deltaTime;

        if (Input.isDown(KeyCode.W) || Input.isDown(KeyCode.S)) {
            computeForward(forward);
            scratch.set(forward).mul(Input.isDown(KeyCode.W) ? speed : -speed);
            position.add(scratch);
            moved = true;
        }
        if (Input.isDown(KeyCode.D) || Input.isDown(KeyCode.A)) {
            computeRight(right);
            scratch.set(right).mul(Input.isDown(KeyCode.D) ? speed : -speed);
            position.add(scratch);
            moved = true;
        }
        if (Input.isDown(KeyCode.E) || Input.isDown(KeyCode.Q)) {
            scratch.set(worldUp).mul(Input.isDown(KeyCode.E) ? speed : -speed);
            position.add(scratch);
            moved = true;
        }
        return moved;
    }

    /** 中键平移：拖拽方向 = 画面内容跟手（相机反向移动），速率随焦点距离缩放 */
    private boolean pan() {
        float dx = Input.mouseDeltaX();
        float dy = Input.mouseDeltaY();
        if (dx == 0.0f && dy == 0.0f) {
            return false;
        }
        float scale = focusDistance * PAN_SPEED;
        computeRight(right);
        // 拖右 → 相机向左（内容右移）；拖下 → 相机沿相机 up 反推（内容下移，保屏幕感）
        scratch.set(right).mul(-dx * scale);
        position.add(scratch);
        computeUp(scratch);
        scratch.mul(dy * scale);
        position.add(scratch);
        return true;
    }

    /**
     * 环绕：绕焦点（pos + forward·distance）水平/俯仰转动，视线始终看向焦点。
     * 每次环绕以按下时刻的视线定焦点，连续拖拽中焦点随鼠标移动更新
     * （下一帧 forward 已转向，焦点跟随球面滑动，体验与 Unity 等价）。
     */
    private boolean orbit() {
        float dx = Input.mouseDeltaX();
        float dy = Input.mouseDeltaY();
        if (dx == 0.0f && dy == 0.0f) {
            return false;
        }
        // 焦点 = 当前位置沿当前视向前推 focusDistance（本帧旋转前的 forward）
        computeForward(forward);
        Vector3f pivotOffset = forward.mul(focusDistance, scratch);   // scratch 借用为焦点偏移
        float px = position.x + pivotOffset.x;
        float py = position.y + pivotOffset.y;
        float pz = position.z + pivotOffset.z;

        yaw += dx * LOOK_SPEED;
        pitch = clampPitch(pitch + dy * LOOK_SPEED);

        // 相机 = 焦点 - 新视向 × 距离（视线由 writeBack 的 yaw/pitch 旋转保证正对焦点）
        computeForward(forward);
        position.set(px - forward.x * focusDistance,
                py - forward.y * focusDistance,
                pz - forward.z * focusDistance);
        return true;
    }

    /**
     * 沿视向推拉（滚轮/Alt+右键共用）：amount &gt; 0 = 拉近（Unity 滚轮向上）；
     * 焦点距离同步收缩（每格 ×0.9 指数步进），环绕越近转得越小，不穿透焦点。
     */
    private boolean dolly(float amount) {
        if (amount == 0.0f) {
            return false;
        }
        computeForward(forward);
        scratch.set(forward).mul(amount);
        position.add(scratch);
        focusDistance = Math.max(MIN_FOCUS_DISTANCE, focusDistance - amount);
        return true;
    }

    // ---------------- 位姿读写 ----------------

    /** 首帧从编辑器相机既有位姿提取 yaw/pitch 与初始焦点距离 */
    private void initPose(Transform transform) {
        Matrix4f localToWorld = transform.getLocalToWorld();
        Vector3f fwd = localToWorld.transformDirection(new Vector3f(0, 0, 1)).normalize();
        // forward = (cosP·sinY, -sinP, cosP·cosY)（rotationY(yaw).rotateX(pitch) 作用于 +Z）
        this.pitch = (float) Math.asin(-fwd.y);
        this.yaw = (float) Math.atan2(fwd.x, fwd.z);
        transform.getWorldPosition(position);
        this.focusDistance = Math.max(MIN_FOCUS_DISTANCE, position.length());
        poseInitialized = true;
    }

    /** 位姿写回（dirty 才写，避免每帧无谓触发脏传播） */
    private void writeBack(Transform transform) {
        rotation.rotationY(yaw).rotateX(pitch);
        transform.setLocalRotation(rotation);
        transform.setLocalPosition(position);
    }

    /** 解析式视向：rotationY(yaw).rotateX(pitch) 作用于 +Z（每帧调用，写入 out 不分配） */
    private void computeForward(Vector3f out) {
        float cosP = (float) Math.cos(pitch);
        out.set((float) (Math.sin(yaw) * cosP), (float) -Math.sin(pitch), (float) (Math.cos(yaw) * cosP));
    }

    /** 解析式水平右向：+Z 视向的右手 90°（yaw-pitch 无 roll，右向恒水平） */
    private void computeRight(Vector3f out) {
        out.set((float) Math.cos(yaw), 0.0f, (float) -Math.sin(yaw));
    }

    /** 解析式相机 up：rotationY(yaw).rotateX(pitch) 作用于 +Y（陡俯视下平移仍保屏幕感） */
    private void computeUp(Vector3f out) {
        out.set((float) (Math.sin(pitch) * Math.sin(yaw)),
                (float) Math.cos(pitch),
                (float) (Math.sin(pitch) * Math.cos(yaw)));
    }

    private static float clampPitch(float p) {
        return Math.max(-PITCH_CLAMP, Math.min(PITCH_CLAMP, p));
    }

}
