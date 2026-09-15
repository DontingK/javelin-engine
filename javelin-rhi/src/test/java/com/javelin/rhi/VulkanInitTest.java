package com.javelin.rhi;

import com.javelin.rhi.impl.VulkanRHI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Vulkan 初始化流程手动测试。
 * <p>
 * 直接运行 {@link #main} 方法，验证：
 * <ol>
 *   <li>GLFW 窗口创建并显示</li>
 *   <li>VulkanRHI 初始化（传入 Surface，内部完成 VkInstance → 物理设备 → 逻辑设备 → Surface）</li>
 *   <li>窗口主循环（按 ESC 退出）</li>
 * </ol>
 */
public class VulkanInitTest {

    private static final Logger log = LoggerFactory.getLogger(VulkanInitTest.class);

    public static void main(String[] args) {
        GLFWSurface surface = null;
        VulkanRHI rhi = null;

        try {
            // 步骤 1：创建 GLFW 窗口（上层负责）
            log.info("========================================");
            log.info("步骤 1：创建 GLFW 窗口");
            log.info("========================================");
            surface = new GLFWSurface();
            surface.creat();
            log.info("窗口创建成功: {}x{}", surface.width(), surface.height());

            // 步骤 2：初始化 VulkanRHI（传入 Surface，RHI 内部完成全部 Vulkan 初始化）
            log.info("========================================");
            log.info("步骤 2：初始化 VulkanRHI");
            log.info("========================================");
            rhi = new VulkanRHI(surface);
//            log.info("VulkanRHI 初始化成功，VkSurfaceKHR={}", rhi.vkSurfaceKHR());

            // 步骤 3：窗口主循环（按 ESC 或点击关闭按钮退出）
            log.info("========================================");
            log.info("步骤 3：进入窗口主循环");
            log.info("========================================");
            long window = surface.getHandle();
            while (!glfwWindowShouldClose(window)) {
                glfwPollEvents();
            }

            log.info("========================================");
            log.info("窗口关闭，测试结束");
            log.info("========================================");

        } catch (Exception e) {
            log.error("测试失败", e);
            System.exit(1);
        } finally {
            if (rhi != null) {
//                rhi.close();
            }
            if (surface != null) {
                surface.close();
            }
        }
    }
}
