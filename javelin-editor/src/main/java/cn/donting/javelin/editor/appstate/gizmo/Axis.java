package cn.donting.javelin.editor.appstate.gizmo;

/**
 * Gizmo 轴判别（X/Y/Z）。
 *
 * <p>工具建轴时给轴节点打 userData 标（key = {@link #USER_DATA_KEY}、value =
 * 本枚举实例），hover/拖拽判定经 {@code node.getUserData().get(USER_DATA_KEY)}
 * 读回判别轴——不再解析节点名字符串；将来旋转/缩放工具的柄可复用同一通道。</p>
 */
public enum Axis {

    X,
    Y,
    Z;

    /** userData 打标 key：value = 本枚举实例 */
    public static final String USER_DATA_KEY = "axis";
}
