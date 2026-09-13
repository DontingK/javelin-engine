import { ref } from 'vue'

/**
 * 输入弹窗选项。
 */
export interface PromptOptions {
  /** 标题 */
  title?: string
  /** 提示内容 */
  message?: string
  /** 输入框默认值 */
  defaultValue?: string
  /** 确认按钮文字 */
  okText?: string
  /** 取消按钮文字 */
  cancelText?: string
  /** 确认回调 */
  ok?: (value: string) => void
  /** 取消回调 */
  cancel?: () => void
}

type RequiredPromptOptions = Required<PromptOptions>

const visible = ref(false)
const inputValue = ref('')
const options = ref<RequiredPromptOptions>({
  title: '输入',
  message: '',
  defaultValue: '',
  okText: '确定',
  cancelText: '取消',
  ok: () => {},
  cancel: () => {}
})

/**
 * 显示输入弹窗。
 *
 * @param opts 弹窗选项
 */
function show(opts: PromptOptions): void {
  options.value = {
    title: '输入',
    message: '',
    defaultValue: '',
    okText: '确定',
    cancelText: '取消',
    ok: () => {},
    cancel: () => {},
    ...opts
  }
  inputValue.value = options.value.defaultValue
  visible.value = true
}

/**
 * 隐藏输入弹窗。
 */
function hide(): void {
  visible.value = false
}

/**
 * 处理确认。
 */
function handleOk(): void {
  options.value.ok?.(inputValue.value)
  hide()
}

/**
 * 处理取消。
 */
function handleCancel(): void {
  options.value.cancel?.()
  hide()
}

/**
 * 全局输入弹窗对象。
 *
 * <p>调用方式：{@code prompt.show({ title: '重命名', message: '请输入新名称', defaultValue: '旧名称', ok: (value) => {} })}</p>
 */
export const prompt = {
  show
}

/**
 * 输入弹窗组合式函数。
 *
 * <p>供 {@link PromptDialog} 组件使用。</p>
 */
export function usePrompt() {
  return {
    visible,
    inputValue,
    options,
    handleOk,
    handleCancel
  }
}
