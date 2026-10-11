import { callJava } from '../bridge/jcefBridge.ts'

/**
 * jar 打包结果 VO（对应 Java 侧 BuildVo）。
 */
export interface BuildVo {
  /** 结束标记（构建日志已经 build.out.* 消息实时推送，返回值仅作完成信号） */
  log: string
}

/**
 * jar 打包参数（打包对话框表单值）。
 *
 * <p>构建前持久化进项目 Game/ 设置（窗口元信息→javelinSettings.settings、启动场景→
 * game.settings 的 defaultSceneGuid；空值/非法值跳过不覆写），Game/ 与 Library/ 随构建
 * 原封复制进产物。构建模式暂不消费。</p>
 */
export interface JarBuildPar {
  /** 窗口标题（空 = 不覆写项目设置） */
  windowTitle?: string
  /** 窗口宽度（非正 = 不覆写） */
  windowWidth?: number
  /** 窗口高度（非正 = 不覆写） */
  windowHeight?: number
  /** 启动场景（Assets 相对路径，空 = 不覆写） */
  startupScene?: string
}

/**
 * jar 打包（构建 > 打包对话框「开始打包」触发）。
 *
 * <p>同步执行项目构建链（dumpRuntimeJars → build → jlink 裁剪 JRE 运行时 → 产物组装到 build/jar/）。
 * 构建日志经 build.out.info/warn/error 消息实时推送（监听方逐行追加），响应到达即
 * 构建完成（返回值为结束标记）。构建时长分钟级——调用期间按钮 loading、取消置灰
 * （构建不可中断）。失败走桥错误协议：Java 侧 tinyfd 弹窗 + 前端 Promise reject。</p>
 *
 * <p>打包对话框表单选项（启动场景/构建模式/窗口元信息）暂不随构建下发——
 * jarBuild 无参签名，选项消费随打包管线批次接入。</p>
 *
 * @returns 构建结果（结束标记）
 */
export function jarBuild(par: JarBuildPar): Promise<BuildVo> {
  return callJava<BuildVo>('/build/jar', par)
}

/**
 * 场景列表 VO（对应 Java 侧 SceneListVo）。
 */
export interface SceneListVo {
  /** 项目场景相对路径列表（资产索引 .scene 枚举，字典序；相对 Assets/） */
  scenes: string[]
  /** 项目默认场景（ProjectSettings.defaultOpenScene，可空） */
  defaultScene?: string
}

/**
 * 拉取项目场景列表（打包对话框「启动场景」下拉数据源）。
 *
 * <p>资产索引（Library/index.asset）经 .scene 后缀枚举；Java 侧每次调用先重载
 * 索引——保存新场景触发的导入当轮即可拾取。默认场景随行返回供下拉默认选中。</p>
 *
 * @returns 场景列表 + 默认场景
 */
export function listScenes(): Promise<SceneListVo> {
  return callJava<SceneListVo>('/build/scenes', {})
}
