/**
 * CefQuery 配置选项。
 */
interface CefQueryOptions {
    /** 请求标识字符串，格式为 url::json。 */
    request: string
    /** 是否持久查询。 */
    persistent: boolean
    /** 成功回调。 */
    onSuccess: (response: string) => void
    /** 失败回调。 */
    onFailure: (code: number, message: string) => void
}

/**
 * CefQuery 调用函数签名。
 */
type CefQueryFunction = (options: CefQueryOptions) => void

declare global {
    interface Window {
        /** JCEF 提供的 JS -> Java 查询函数。 */
        cefQuery?: CefQueryFunction
    }
}



/**
 * 通过 JCEF CefQuery 调用 Java 后端方法。
 *
 * <p>请求格式为 {@code url::json}，由 {@link JsJavaBridgeHandler} 解析并路由到
 * {@code com.javelin.editor.jcef} 包下带 {@code @BridgeMethod} 注解的方法。</p>
 *
 * <p>此函数仅在 JCEF 环境中可用；浏览器开发模式下会返回桥接不可用错误。</p>
 *
 * @param url 桥接 URL，例如 {@code /scene/entity/create-empty}
 * @param data 请求参数对象，无参时可为 undefined
 * @returns 链式结果对象
 */
export function callJava<T>(url: string, data?: unknown): Promise<T> {
    return callJavaByCefQuery<T>(url, data)
}

/**
 * 通过 CefQuery 发起调用。
 *
 * @param url 桥接 URL
 * @param data 请求参数
 * @returns 链式结果对象
 */
function callJavaByCefQuery<T>(url: string, data?: unknown): Promise<T> {
    const request = buildRequest(url, data)
    return new Promise<T>((resolve, reject) => {
        window.cefQuery!({
            request,
            persistent: false,
            onSuccess: (response) => {
                resolve(JSON.parse(response))
            },
            onFailure: (_code, message) => {
                reject(new Error(message))
            }
        })
    })
}

/**
 * 构建 CefQuery 请求字符串。
 *
 * @param url 桥接 URL
 * @param data 请求参数
 * @returns url::json 格式字符串
 */
function buildRequest(url: string, data?: unknown): string {
    if (data === undefined) {
        return url + '::{}'
    }
    return url + '::' + JSON.stringify(data)
}
