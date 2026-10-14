import { callJava } from '../bridge/jcefBridge.ts'

/**
 * 变换工具（对应 Java 侧 TransformTools.getName()，线上惯例小写、后端大小写不敏感解析）。
 *
 * <p>当前仅 move（移动）——rotate/scale 工具未落地，工具栏按钮置灰占位。</p>
 */
export type TransformTool = 'move'

/**
 * Gizmo 坐标空间：仅影响 Gizmo 轴朝向与拖拽轴，Inspector 恒 local 显示
 * （Unity Global/Local 口径）。world=世界轴（默认，现状语义）、local=跟随目标局部轴。
 */
export type GizmoSpace = 'world' | 'local'

/**
 * 切换变换工具（Java 侧 TransformGizmoAppState 工具意图镜像，引擎线程帧内消费）。
 *
 * @param tool 目标工具名
 */
export function setTransformTool(tool: TransformTool): Promise<void> {
  return callJava<void>('/gizmo/setTool', {tool})
}

/**
 * 切换 Gizmo 坐标空间（局部/世界）。
 *
 * @param space 目标空间
 */
export function setGizmoSpace(space: GizmoSpace): Promise<void> {
  return callJava<void>('/gizmo/setSpace', {space})
}
