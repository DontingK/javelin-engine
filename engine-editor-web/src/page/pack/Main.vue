<template>
  <div class="pack-dialog">
    <div class="pack-form">
      <div class="field-row">
        <label class="field-label">构建形态</label>
        <JeSelect v-model="buildForm" :options="buildFormOptions"/>
      </div>
      <div class="field-row">
        <label class="field-label">启动场景</label>
        <JeSelect v-model="startupScene" :options="sceneOptions" placeholder="无场景（Assets/ 下无 .scene）"/>
      </div>
      <div v-if="buildForm === 'aot'" class="field-row">
        <label class="field-label">构建模式</label>
        <JeSelect v-model="buildMode" :options="buildModeOptions"/>
      </div>
      <div class="section-title">窗口元信息</div>
      <div class="field-row">
        <label class="field-label">窗口标题</label>
        <JeInput v-model="windowTitle" placeholder="游戏窗口标题"/>
      </div>
      <div class="field-row">
        <label class="field-label">宽度</label>
        <JeNumberInput v-model="windowWidth" :integer="true" :min="1"/>
      </div>
      <div class="field-row">
        <label class="field-label">高度</label>
        <JeNumberInput v-model="windowHeight" :integer="true" :min="1"/>
      </div>
    </div>
    <div v-if="logLines.length || packing" class="pack-log">
      <div class="log-status" :class="{ failed: packFailed }">{{ statusText }}</div>
      <div ref="logBoxRef" class="log-text">
        <div v-for="(line, i) in logLines" :key="i" class="log-line" :class="'level-' + line.level">{{ line.text }}</div>
      </div>
    </div>
    <div class="pack-footer">
      <JeButton type="secondary" :disabled="packing" @click="onCancel">取消</JeButton>
      <JeButton type="primary" :loading="packing" @click="onPack">开始打包</JeButton>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import javaScriptBridge from '../../bridge/javaScriptBridge.ts'
import JeButton from '../../components/common/JeButton.vue'
import JeInput from '../../components/input/JeInput.vue'
import JeNumberInput from '../../components/input/JeNumberInput.vue'
import JeSelect from '../../components/input/JeSelect.vue'
import type { SelectOption } from '../../components/input/JeSelect.vue'
import { packBuild, listScenes } from '../../service/buildService.ts'

/**
 * 打包选项对话框（构建 > 打包 命令弹出的 CEF 对话框页，宿主为 Java 侧 Dialog 容器）。
 *
 * <p>「开始打包」已接真构建（/build/pack → GradleProject.jarBuild / aotBuild 构建形态分流）：
 * 构建日志经 build.out.info/warn/error 消息<b>实时推送</b>（Java 侧 ConsoleOut →
 * JavaScriptBridge → 调用者浏览器），本页监听逐行追加、自动滚底；响应到达即构建完成
 * （返回值为结束标记），期间按钮 loading、取消置灰（构建不可中断）；失败走桥错误
 * 协议（Java 侧 tinyfd 弹窗，此处 Promise reject 错误行内联显示）。关窗仍走对话框
 * 右上角 X（Java 侧 DISPOSE_ON_CLOSE）。</p>
 *
 * <p>「构建形态」JAR/AOT 分流两条构建链（2026-10-13 落地）：JAR 走 jlink JRE 运行时
 * 链（产物 Builds/jar）、AOT 走 GraalVM native-image 链（产物 Builds/aot——exe +
 * 外置 lib/*.dll，AotGameMain 设 org.lwjgl.librarypath=./lib 防 natives 打进 exe）。
 * 「启动场景」下拉已接真数据（onMounted 拉取 /build/scenes——资产索引 .scene
 * 枚举，默认选中项目默认场景）；表单参数随构建下发——窗口元信息/启动场景构建前
 * 持久化进项目 Game/ 设置（javelinSettings.settings / game.settings，空值跳过），
 * Game/ 与 Library/ 原封复制进产物即取到新值；构建模式仅 AOT 形态消费（Debug→
 * quickBuild / Release→全优化，经 -PbuildMode 下发；JAR 形态下该行隐藏）。</p>
 */

/** 构建形态选项（JAR=jlink JRE 运行时；AOT=GraalVM native-image 原生可执行） */
const buildFormOptions: SelectOption[] = [
  {value: 'jar', label: 'JAR'},
  {value: 'aot', label: 'AOT'},
]

/** 构建模式选项（AOT 形态下消费：Debug→quickBuild 快速构建 / Release→全优化） */
const buildModeOptions: SelectOption[] = [
  {value: 'debug', label: 'Debug'},
  {value: 'release', label: 'Release'},
]

/** 启动场景选项（onMounted 从 /build/scenes 拉取填充） */
const sceneOptions = ref<SelectOption[]>([])

/** 启动场景（资产相对路径，如 scenes/default.scene——与项目设置口径一致） */
const startupScene = ref('')

// 启动场景下拉数据源：资产索引 .scene 枚举（Java 侧先重载索引——保存新场景
// 触发的导入当轮即可拾取）；默认选中项目默认场景，不在列表则取第一项
onMounted(async () => {
  try {
    const vo = await listScenes()
    sceneOptions.value = vo.scenes.map(path => ({value: path, label: path}))
    if (vo.defaultScene != null && vo.scenes.includes(vo.defaultScene)) {
      startupScene.value = vo.defaultScene
    } else if (vo.scenes.length > 0) {
      startupScene.value = vo.scenes[0]
    }
  } catch (e) {
    console.warn('[PackDialog] 拉取场景列表失败', e)
  }
})
/** 构建形态（jar / aot——Java 侧分流 jarBuild / aotBuild 构建链） */
const buildForm = ref('jar')
/** 构建模式（AOT 形态下随构建经 -PbuildMode 下发） */
const buildMode = ref('debug')
/** 窗口标题 */
const windowTitle = ref('')
/** 窗口默认宽度 */
const windowWidth = ref(1280)
/** 窗口默认高度 */
const windowHeight = ref(720)

/** 构建执行中（按钮 loading、取消置灰） */
const packing = ref(false)
/** 构建失败标记（状态行显示失败） */
const packFailed = ref(false)

/** 构建日志行（build.out.* 消息实时追加） */
interface LogLine {
  level: 'info' | 'warn' | 'error'
  text: string
}

/** 日志行列表（流式追加，失败时含错误行） */
const logLines = ref<LogLine[]>([])
/** 日志滚动容器（新行自动滚底） */
const logBoxRef = ref<HTMLElement | null>(null)

/** build.out.* 消息处理器工厂：行追加 */
function makeLineHandler(level: LogLine['level']) {
  return (data: string) => {
    logLines.value.push({level, text: data})
  }
}

const onInfo = makeLineHandler('info')
const onWarn = makeLineHandler('warn')
const onError = makeLineHandler('error')

javaScriptBridge.addMsgListener('build.out.info', onInfo)
javaScriptBridge.addMsgListener('build.out.warn', onWarn)
javaScriptBridge.addMsgListener('build.out.error', onError)
onBeforeUnmount(() => {
  javaScriptBridge.removeMsgListener('build.out.info', onInfo)
  javaScriptBridge.removeMsgListener('build.out.warn', onWarn)
  javaScriptBridge.removeMsgListener('build.out.error', onError)
})

// 新行到达滚到底部（v0.1 简单滚底，不打断上翻检测后置）
watch(() => logLines.value.length, async () => {
  await nextTick()
  if (logBoxRef.value != null) {
    logBoxRef.value.scrollTop = logBoxRef.value.scrollHeight
  }
})

/** 状态行文本（构建中/完成/失败） */
const statusText = computed(() => {
  if (packing.value) {
    return '构建中（Gradle 构建链，可能需要数分钟）…'
  }
  return packFailed.value ? '打包失败（详情见日志，Java 侧已弹错误窗）' : '打包完成'
})

/**
 * 开始打包：同步触发构建（构建形态分流 jar/aot 链），完成展示日志（失败内联显示错误信息）。
 */
async function onPack(): Promise<void> {
  if (packing.value) {
    return
  }
  packing.value = true
  packFailed.value = false
  logLines.value = []
  try {
    // 日志经 build.out.* 消息实时下发；表单值先持久化进 Game/（日志区可见写入行）；
    // 响应到达即构建完成（返回值为结束标记）
    await packBuild({
      buildForm: buildForm.value,
      buildMode: buildMode.value,
      windowTitle: windowTitle.value,
      windowWidth: windowWidth.value,
      windowHeight: windowHeight.value,
      startupScene: startupScene.value,
    })
  } catch (e) {
    packFailed.value = true
    logLines.value.push({level: 'error', text: e instanceof Error ? e.message : String(e)})
    console.warn('[PackDialog] 打包失败', e)
  } finally {
    packing.value = false
  }
}

/**
 * 取消：关窗桥未接，仅记日志（关窗走对话框右上角 X）。
 */
function onCancel(): void {
  console.log('[PackDialog] 取消（关窗走对话框右上角 X，桥随管线接入）')
}
</script>

<style scoped>
.pack-dialog {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  background: var(--editor-surface);
}

.pack-form {
  flex: 1;
  padding: 2px;
  overflow-y: auto;
}

/* 字段行：label 列与控件列 4px（两列 label/控件布局专用间隔，AGENTS 间距规范例外），
   行距 2px */
.field-row {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 2px;
}

.field-label {
  flex-shrink: 0;
  width: 60px;
  font-size: 12px;
  color: var(--editor-text-dim);
}

.section-title {
  margin: 2px 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--editor-text-heading);
}

.pack-footer {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
  padding: 2px;
  border-top: 1px solid var(--editor-border);
}

/* 构建日志区：出现即占余下空间（flex:1），内部滚动 */
.pack-log {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0 2px 2px;
  background: var(--editor-surface-light);
  border: 1px solid var(--editor-border);
  border-radius: 3px;
  overflow: hidden;
}

.log-status {
  flex-shrink: 0;
  padding: 2px;
  font-size: 12px;
  color: var(--editor-text-dim);
}

.log-status.failed {
  color: #dc3545;
}

.log-text {
  flex: 1;
  min-height: 0;
  padding: 2px;
  overflow-y: auto;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 11px;
  line-height: 1.4;
  color: var(--editor-text);
}

.log-line {
  white-space: pre-wrap;
  word-break: break-all;
}

/* 级别配色：error 沿用 JeButton danger 同款红；warn 无对应色板变量，同族琥珀硬编码 */
.level-error {
  color: #dc3545;
}

.level-warn {
  color: #cc8800;
}
</style>
