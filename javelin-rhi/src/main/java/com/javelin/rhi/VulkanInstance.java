package com.javelin.rhi;

import com.javelin.rhi.exception.VulkanException;
import com.javelin.rhi.render.Platform;
import com.javelin.rhi.render.RenderSurface;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
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

            // 校验层（仅 Debug）
            if (debug) {
                PointerBuffer layerBuf = stack.mallocPointer(1);
                layerBuf.put(stack.UTF8("VK_LAYER_KHRONOS_validation"));
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
}
