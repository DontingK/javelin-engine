import { callJava } from '../bridge/jcefBridge.ts'

/**
 * Inspector 滑条范围（对应 Java 侧 InspectorFieldVo.Range）。
 */
export interface InspectorRange {
  /** 最小值 */
  min: number
  /** 最大值 */
  max: number
  /** 步长 */
  step: number
}

/**
 * Inspector 枚举选项（对应 Java 侧 InspectorFieldVo.Option）。
 */
export interface InspectorOption {
  /** 枚举常量名（回写值） */
  value: string
  /** 显示名 */
  label: string
}

/**
 * Inspector 字段 VO（对应 Java 侧 InspectorFieldVo）。
 */
export interface InspectorFieldVo {
  /** 字段宿主对象 id（Node/Component，/inspector/setValue 回写锚点） */
  objId: number
  /** 字段名（Java 侧反射定位宿主上的字段） */
  fieldName: string
  /** 显示名 */
  name: string
  /** 值类型（= Java 侧 InspectorValueType 常量名：INT/FLOAT/COLOR/VEC3_F/ROTATION/ENUM/BOOLEAN/LAYER_MASK/STRING/RECT/TAG） */
  valueType: string
  /** 字段当前值（按 valueType 序列化的字符串） */
  value: string | null
  /** 滑条范围（@InsIntRange/@InsFloatRange 投影；null = 普通输入框） */
  range: InspectorRange | null
  /** 枚举可选项（ENUM 字段；其他类型 null） */
  options: InspectorOption[] | null
}

/**
 * 组件分组 VO（对应 Java 侧 ComponentInspectorVo）。
 */
export interface ComponentInspectorVo {
  /** 组件 id（ObjectCache 反查锚点） */
  objId: number
  /** 组件显示名 */
  name: string
  /** 组件启用态（分组头部勾选框） */
  enabled: boolean
  /** 必需组件（如 Transform）：不可禁用/移除，不渲染头部勾选 */
  required: boolean
  /** 组件内可检视字段列表 */
  fields: InspectorFieldVo[]
}

/**
 * /inspector/info 节点快照 VO（对应 Java 侧 NodeInspectorVo）。
 *
 * <p>ready=false = 编辑器未就绪/节点不存在或已被 GC/拉取超时，其余字段无效——
 * 消费方清面板或节流重试（启动竞态兜底，同 SceneTreeVo 口径）。</p>
 */
export interface NodeInspectorVo {
  /** 就绪标记 */
  ready: boolean
  /** 节点 id */
  objId: number
  /** 节点名 */
  nodeName: string
  /** 节点 Layer 索引（0~30，Unity 口径单层；勿按位掩码语义取值） */
  layer: number
  /** Layer 名列表（SettingManager 直读投影，索引=层号；空串=未命名槽位不渲染） */
  layerNames: string[] | null
  /** 节点 tags 名列表（Node.tags 投影，头部 Tag 下拉当前值；空显示内建默认 "Untagged"） */
  tags: string[] | null
  /** Tag 注册表名列表（下拉选项；含内建兜底 "Untagged"） */
  tagNames: string[] | null
  /** 自身激活态（层级生效判定在 Java 侧） */
  active: boolean
  /** 组件字段分组列表 */
  components: ComponentInspectorVo[] | null
}

/**
 * /inspector/setValue 请求参数（对应 Java 侧 InspectorValuePar）。
 */
export interface InspectorValuePar {
  /** 字段宿主对象 id（Node/Component） */
  objId: number
  /** 字段名 */
  fieldName: string
  /** 值类型（InspectorValueType 常量名） */
  valueType: string
  /** 新值字符串（按 valueType 序列化格式） */
  value: string
}

/**
 * 拉取节点 Inspector 快照（Java 侧经引擎主线程帧边界反射收集）。
 *
 * <p>请求体须与 Java 侧 IdPar 严格一致（Jackson 默认拒绝未知字段）。</p>
 *
 * @param nodeId 节点 id
 */
export function loadInspectorInfo(nodeId: number): Promise<NodeInspectorVo> {
  return callJava<NodeInspectorVo>('/inspector/info', {id: nodeId})
}

/**
 * 回写 Inspector 字段值（Java 侧解析 + setter 写入 + 场景置脏）。
 *
 * <p>请求体须与 Java 侧 InspectorValuePar 严格一致（四字段勿多传）。
 * fire-and-forget 语义：乐观更新由调用方完成，失败仅日志（提示类异常
 * 由 Java 侧弹原生框）。</p>
 *
 * @param par 回写参数
 */
export function setInspectorValue(par: InspectorValuePar): Promise<void> {
  return callJava<void>('/inspector/setValue', par)
}
