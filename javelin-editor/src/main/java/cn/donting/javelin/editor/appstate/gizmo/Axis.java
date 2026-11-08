package cn.donting.javelin.editor.appstate.gizmo;

/**
 * Gizmo 柄判别（X/Y/Z + 中心均匀柄）。
 *
 * <p>工具建柄时给柄节点打 userData 标（key = {@link #USER_DATA_KEY}、value =
 * 本枚举实例），hover/拖拽判定经 {@code node.getUserData().get(USER_DATA_KEY)}
 * 读回判别柄——不再解析节点名字符串。X/Y/Z = 单轴柄（移动轴/旋转环/缩放轴各自
 * 一式）；{@link #UNIFORM} = 缩放的中心均匀柄（无方向语义，一柄管三轴同倍）。</p>
 */
public enum Axis {

    X,
    Y,
    Z,

    /** 中心均匀柄（三轴同倍缩放；无方向——工具按"多轴"语义消费） */
    UNIFORM;

    /** userData 打标 key：value = 本枚举实例 */
    public static final String USER_DATA_KEY = "axis";
}
