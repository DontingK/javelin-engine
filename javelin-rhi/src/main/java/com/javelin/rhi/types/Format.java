package com.javelin.rhi.types;

/**
 * 像素格式。
 * 对照 VkFormat，仅包含引擎起步所需格式，按需扩展。
 * 后缀约定：UNORM=无符号归一化（0..255 等映射 0..1）、SNORM=有符号归一化、
 * UINT/SINT=原始整值（不做归一化）、SFLOAT=浮点、SRGB=伽马编码存储（采样时硬件自动转线性）。
 */
public enum Format {
    /** 未定义格式（占位） */
    UNDEFINED,              // VK_FORMAT_UNDEFINED

    /** 单通道 8 位无符号归一化 */
    R8_UNORM,               // VK_FORMAT_R8_UNORM
    /** 单通道 8 位有符号归一化 */
    R8_SNORM,               // VK_FORMAT_R8_SNORM
    /** 单通道 8 位无符号整数 */
    R8_UINT,                // VK_FORMAT_R8_UINT
    /** 单通道 8 位有符号整数 */
    R8_SINT,                // VK_FORMAT_R8_SINT

    /** 双通道 8 位无符号归一化 */
    R8G8_UNORM,             // VK_FORMAT_R8G8_UNORM
    /** 双通道 8 位有符号归一化 */
    R8G8_SNORM,             // VK_FORMAT_R8G8_SNORM
    /** 双通道 8 位无符号整数 */
    R8G8_UINT,              // VK_FORMAT_R8G8_UINT
    /** 双通道 8 位有符号整数 */
    R8G8_SINT,              // VK_FORMAT_R8G8_SINT

    /** RGBA 四通道 8 位无符号归一化（最常用颜色格式） */
    R8G8B8A8_UNORM,         // VK_FORMAT_R8G8B8A8_UNORM
    /** RGBA 四通道 8 位有符号归一化 */
    R8G8B8A8_SNORM,         // VK_FORMAT_R8G8B8A8_SNORM
    /** RGBA 四通道 8 位无符号整数 */
    R8G8B8A8_UINT,          // VK_FORMAT_R8G8B8A8_UINT
    /** RGBA 四通道 8 位有符号整数 */
    R8G8B8A8_SINT,          // VK_FORMAT_R8G8B8A8_SINT
    /** RGBA 四通道 8 位 sRGB（伽马编码，采样自动转线性） */
    R8G8B8A8_SRGB,          // VK_FORMAT_R8G8B8A8_SRGB
    /** BGRA 四通道 8 位无符号归一化（通道序 R/B 互换，交换链常见） */
    B8G8R8A8_UNORM,         // VK_FORMAT_B8G8R8A8_UNORM
    /** BGRA 四通道 8 位 sRGB */
    B8G8R8A8_SRGB,          // VK_FORMAT_B8G8R8A8_SRGB

    /** 单通道 16 位半精度浮点 */
    R16_SFLOAT,             // VK_FORMAT_R16_SFLOAT
    /** 双通道 16 位半精度浮点 */
    R16G16_SFLOAT,          // VK_FORMAT_R16G16_SFLOAT
    /** RGBA 四通道 16 位半精度浮点（HDR 渲染目标） */
    R16G16B16A16_SFLOAT,    // VK_FORMAT_R16G16B16A16_SFLOAT

    /** 单通道 16 位无符号整数 */
    R16_UINT,               // VK_FORMAT_R16_UINT
    /** 单通道 16 位有符号整数 */
    R16_SINT,               // VK_FORMAT_R16_SINT
    /** 双通道 16 位无符号整数 */
    R16G16_UINT,            // VK_FORMAT_R16G16_UINT
    /** 双通道 16 位有符号整数 */
    R16G16_SINT,            // VK_FORMAT_R16G16_SINT
    /** RGBA 四通道 16 位无符号整数 */
    R16G16B16A16_UINT,      // VK_FORMAT_R16G16B16A16_UINT
    /** RGBA 四通道 16 位有符号整数 */
    R16G16B16A16_SINT,      // VK_FORMAT_R16G16B16A16_SINT

    /** 单通道 32 位单精度浮点 */
    R32_SFLOAT,             // VK_FORMAT_R32_SFLOAT
    /** 双通道 32 位单精度浮点 */
    R32G32_SFLOAT,          // VK_FORMAT_R32G32_SFLOAT
    /** 三通道 32 位单精度浮点 */
    R32G32B32_SFLOAT,       // VK_FORMAT_R32G32B32_SFLOAT
    /** RGBA 四通道 32 位单精度浮点 */
    R32G32B32A32_SFLOAT,    // VK_FORMAT_R32G32B32A32_SFLOAT

    /** 单通道 32 位无符号整数 */
    R32_UINT,               // VK_FORMAT_R32_UINT
    /** 单通道 32 位有符号整数 */
    R32_SINT,               // VK_FORMAT_R32_SINT
    /** 双通道 32 位无符号整数 */
    R32G32_UINT,            // VK_FORMAT_R32G32_UINT
    /** 双通道 32 位有符号整数 */
    R32G32_SINT,            // VK_FORMAT_R32G32_SINT
    /** RGBA 四通道 32 位无符号整数 */
    R32G32B32A32_UINT,      // VK_FORMAT_R32G32B32A32_UINT
    /** RGBA 四通道 32 位有符号整数 */
    R32G32B32A32_SINT,      // VK_FORMAT_R32G32B32A32_SINT

    /** 16 位深度（归一化存储） */
    D16_UNORM,              // VK_FORMAT_D16_UNORM
    /** 32 位浮点深度（高精度首选） */
    D32_SFLOAT,             // VK_FORMAT_D32_SFLOAT
    /** 24 位深度 + 8 位模板打包为 32 位 */
    D24_UNORM_S8_UINT,      // VK_FORMAT_D24_UNORM_S8_UINT
    /** 32 位浮点深度 + 8 位模板（各自独立存储） */
    D32_SFLOAT_S8_UINT,     // VK_FORMAT_D32_SFLOAT_S8_UINT
    /** 仅 8 位模板，无深度 */
    S8_UINT,                // VK_FORMAT_S8_UINT

    /** BC1 压缩 RGB（4x4 块 8 字节，无 alpha） */
    BC1_RGB_UNORM,          // VK_FORMAT_BC1_RGB_UNORM_BLOCK
    /** BC1 压缩 RGB，sRGB */
    BC1_RGB_SRGB,           // VK_FORMAT_BC1_RGB_SRGB_BLOCK
    /** BC1 压缩 RGBA（1 位开/关 alpha） */
    BC1_RGBA_UNORM,         // VK_FORMAT_BC1_RGBA_UNORM_BLOCK
    /** BC1 压缩 RGBA，sRGB */
    BC1_RGBA_SRGB,          // VK_FORMAT_BC1_RGBA_SRGB_BLOCK
    /** BC2 压缩 RGBA（BC1 颜色 + 4 位显式 alpha） */
    BC2_UNORM,              // VK_FORMAT_BC2_UNORM_BLOCK
    /** BC2 压缩 RGBA，sRGB */
    BC2_SRGB,               // VK_FORMAT_BC2_SRGB_BLOCK
    /** BC3 压缩 RGBA（BC1 颜色 + BC4 压缩 8 位 alpha，平滑透明首选） */
    BC3_UNORM,              // VK_FORMAT_BC3_UNORM_BLOCK
    /** BC3 压缩 RGBA，sRGB */
    BC3_SRGB,               // VK_FORMAT_BC3_SRGB_BLOCK
    /** BC4 压缩单通道 8 位（高度图等） */
    BC4_UNORM,              // VK_FORMAT_BC4_UNORM_BLOCK
    /** BC4 压缩单通道，有符号 */
    BC4_SNORM,              // VK_FORMAT_BC4_SNORM_BLOCK
    /** BC5 压缩双通道（法线贴图常用） */
    BC5_UNORM,              // VK_FORMAT_BC5_UNORM_BLOCK
    /** BC5 压缩双通道，有符号 */
    BC5_SNORM,              // VK_FORMAT_BC5_SNORM_BLOCK
    /** BC6H 无符号半精度 HDR 压缩（亮度域存储） */
    BC6H_UFLOAT,            // VK_FORMAT_BC6H_UFLOAT_BLOCK
    /** BC6H 有符号半精度 HDR 压缩 */
    BC6H_SFLOAT,            // VK_FORMAT_BC6H_SFLOAT_BLOCK
    /** BC7 高质量 RGBA 块压缩（4x4 块 16 字节） */
    BC7_UNORM,              // VK_FORMAT_BC7_UNORM_BLOCK
    /** BC7 高质量 RGBA 块压缩，sRGB */
    BC7_SRGB;               // VK_FORMAT_BC7_SRGB_BLOCK

    /** 非压缩格式的每像素字节数（读回拷贝容量计算用）；压缩格式抛出不支持 */
    public int bytesPerPixel() {
        return switch (this) {
            case R8_UNORM, R8_SNORM, R8_UINT, R8_SINT, S8_UINT -> 1;
            case R8G8_UNORM, R8G8_SNORM, R8G8_UINT, R8G8_SINT,
                 R16_SFLOAT, R16_UINT, R16_SINT, D16_UNORM -> 2;
            case R8G8B8A8_UNORM, R8G8B8A8_SNORM, R8G8B8A8_UINT, R8G8B8A8_SINT, R8G8B8A8_SRGB,
                 B8G8R8A8_UNORM, B8G8R8A8_SRGB,
                 R16G16_SFLOAT, R16G16_UINT, R16G16_SINT,
                 R32_SFLOAT, R32_UINT, R32_SINT,
                 D32_SFLOAT, D24_UNORM_S8_UINT -> 4;
            case R16G16B16A16_SFLOAT, R16G16B16A16_UINT, R16G16B16A16_SINT,
                 R32G32_SFLOAT, R32G32_UINT, R32G32_SINT,
                 D32_SFLOAT_S8_UINT -> 8;
            case R32G32B32_SFLOAT -> 12;
            case R32G32B32A32_SFLOAT, R32G32B32A32_UINT, R32G32B32A32_SINT -> 16;
            default -> throw new UnsupportedOperationException("压缩格式无逐像素字节数: " + this);
        };
    }
}
