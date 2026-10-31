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

            // 校验层（仅 Debug；本机未安装时降级为无校验运行，避免 VK_ERROR_LAYER_NOT_PRESENT）
            if (debug) {
                if (isLayerAvailable(stack, VALIDATION_LAYER)) {
                    PointerBuffer layerBuf = stack.mallocPointer(1);
                    layerBuf.put(stack.UTF8(VALIDATION_LAYER));
                    layerBuf.flip();
                    createInfo.ppEnabledLayerNames(layerBuf);
                } else {
                    log.warn("Validation layer {} not available; continuing without it "
                            + "(install Vulkan SDK to enable validation)", VALIDATION_LAYER);
                }
            }

            // 创建实例
            PointerBuffer instanceBuf = stack.mallocPointer(1);
            int result = vkCreateInstance(createInfo, null, instanceBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create Vulkan instance");
            }

            this.vkInstance = new VkInstance(instanceBuf.get(0), createInfo);
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
        vkDestroyInstance(vkInstance, null);
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
