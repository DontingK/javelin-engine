package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.exception.VulkanException;
import cn.donting.javelin.rhi.render.Platform;
import cn.donting.javelin.rhi.render.RenderSurface;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;
import static org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;

/**
 * Vulkan 实例实现。
 * 对照设计文档第 23.4a 节阶段 1~2。
 */
final class VulkanInstance implements Instance {

    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    private static final Logger log = LoggerFactory.getLogger(VulkanInstance.class);

    private final VkInstance vkInstance;
    private final boolean debug;
    private volatile boolean disposed;

    /** 校验层消息回调（LWJGL Callback 靠 native 地址存活，必须强引用到 dispose） */
    private VkDebugUtilsMessengerCallbackEXT debugCallback;
    private long debugMessenger = VK_NULL_HANDLE;

    VulkanInstance(RHI.RHIInstanceConfig config, Platform platform) {
        this.debug = config.debug();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 应用信息
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack);
            appInfo.sType$Default();
            appInfo.pApplicationName(stack.UTF8(config.appName()));
            appInfo.applicationVersion(config.appVersion());
            appInfo.pEngineName(stack.UTF8("JavelinEngine"));
            appInfo.engineVersion(1);
            appInfo.apiVersion(VK_API_VERSION_1_3);

            // 实例创建信息
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack);
            createInfo.sType$Default();
            createInfo.pApplicationInfo(appInfo);

            // 扩展：surface + 平台特定
            List<String> extensions = new ArrayList<>();
            extensions.add(VK_KHR_SURFACE_EXTENSION_NAME);
            extensions.add(platform.surfaceExtension);

            if (debug) {
                extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            }

            PointerBuffer extBuf = stack.mallocPointer(extensions.size());
            for (String ext : extensions) {
                extBuf.put(stack.UTF8(ext));
            }
            extBuf.flip();
            createInfo.ppEnabledExtensionNames(extBuf);

            // 校验层（仅 Debug）：无层即 fail-fast 不启动（2026-11-10 拍板，红线 15 /
            // Pitfalls #19 收口——静默降级会让「校验层开了却听不见」持续掩盖问题）；
            // 关掉 engineDebug 可免校验层启动
            if (debug) {
                if (!isLayerAvailable(stack, VALIDATION_LAYER)) {
                    throw new IllegalStateException("Vulkan validation layer '" + VALIDATION_LAYER
                            + "' is not available. Install the Vulkan SDK (LunarG) to enable "
                            + "validation, or launch with --debug=false to run without it.");
                }
                PointerBuffer layerBuf = stack.mallocPointer(1);
                layerBuf.put(stack.UTF8(VALIDATION_LAYER));
                layerBuf.flip();
                createInfo.ppEnabledLayerNames(layerBuf);
            }

            // 创建实例
            PointerBuffer instanceBuf = stack.mallocPointer(1);
            int result = vkCreateInstance(createInfo, null, instanceBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create Vulkan instance");
            }

            this.vkInstance = new VkInstance(instanceBuf.get(0), createInfo);
        }

        // 校验层消息路由（Engine_DebugTools_Plan §2.2）：实例已建，紧接注册 messenger
        if (debug) {
            createDebugMessenger();
        }
    }

    /**
     * 注册 DebugUtilsMessenger：校验层消息（WARNING/ERROR × VALIDATION/GENERAL/PERFORMANCE）
     * 回调路由到 slf4j，日志前缀 {@code [VVL]}。回调跑在驱动线程，只做字符串格式化 + log
     * （禁止其中调用 Vulkan / 做大分配）。
     */
    private void createDebugMessenger() {
        debugCallback = new VkDebugUtilsMessengerCallbackEXT() {
            @Override
            public int invoke(int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
                VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                String idName = data.pMessageIdNameString();
                String body = data.pMessageString();
                String line = "[VVL] " + (idName != null ? idName + " " : "") + body;
                if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                    log.error(line);
                } else if ((messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) != 0) {
                    log.warn(line);
                }
                // 不中断调用方（官方建议恒返 VK_FALSE）
                return VK_FALSE;
            }
        };

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
            createInfo.sType$Default();
            // 严重度：WARNING|ERROR（VERBOSE/INFO 噪声大，不收；后续排查要开只改此处一行）
            createInfo.messageSeverity(
                    VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
            createInfo.messageType(
                    VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
            createInfo.pfnUserCallback(debugCallback);

            long[] messenger = new long[1];
            int result = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(vkInstance, createInfo, null, messenger);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create Vulkan debug utils messenger");
            }
            debugMessenger = messenger[0];
        }
    }

    @Override
    public Device createDevice(RHIDevicePrefs prefs, RenderSurface surface) {
        checkDisposed();
        return new VulkanDevice(this, vkInstance, prefs, surface, debug);
    }

    @Override
    public Surface createSurface(RenderSurface renderSurface) {
        checkDisposed();
        return new VulkanSurface(this, vkInstance, renderSurface);
    }

    @Override
    public long handle() {
        return vkInstance.address();
    }

    VkInstance vkInstance() {
        return vkInstance;
    }

    boolean isDebug() {
        return debug;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        // messenger 生命周期必须短于实例：先销毁 messenger，再毁实例，最后释放回调
        if (debugMessenger != VK_NULL_HANDLE) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(vkInstance, debugMessenger, null);
            debugMessenger = VK_NULL_HANDLE;
        }
        vkDestroyInstance(vkInstance, null);
        if (debugCallback != null) {
            debugCallback.free();
            debugCallback = null;
        }
    }

    private void checkDisposed() {
        if (disposed) {
            throw new IllegalStateException("Instance has been disposed");
        }
    }

    /** 枚举实例层，检查指定层是否存在于本机 */
    private static boolean isLayerAvailable(MemoryStack stack, String layerName) {
        IntBuffer countBuf = stack.mallocInt(1);
        int result = vkEnumerateInstanceLayerProperties(countBuf, null);
        if (result != VK_SUCCESS) {
            return false;
        }
        int count = countBuf.get(0);
        if (count == 0) {
            return false;
        }
        VkLayerProperties.Buffer layers = VkLayerProperties.malloc(count, stack);
        result = vkEnumerateInstanceLayerProperties(countBuf, layers);
        if (result != VK_SUCCESS) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (layerName.equals(layers.get(i).layerNameString())) {
                return true;
            }
        }
        return false;
    }
}
