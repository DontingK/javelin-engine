# Third-Party Notices

This project includes or depends on the following third-party components.
Each component remains under its own license; the project license (see
[LICENSE](LICENSE)) applies to the project's own code only.

| Component | Version | License | Homepage |
|-----------|---------|---------|----------|
| LWJGL (Vulkan / GLFW / shaderc / STB / tinyfd / JAWT / Yoga bindings) | 3.4.2 | BSD 3-Clause | https://lwjgl.org |
| shaderc (SPIR-V compiler, via LWJGL) | via LWJGL | Apache-2.0 | https://github.com/google/shaderc |
| JOML | 1.10.8 | MIT | https://github.com/JOML-CI/JOML |
| slf4j | 2.0.18 | MIT | https://www.slf4j.org |
| Logback | 1.5.38 | EPL-2.0 or LGPL-2.1 (dual license) | https://logback.qos.ch |
| FlatLaf | 3.5.4 | Apache-2.0 | https://www.formdev.com/flatlaf/ |
| JSVG | 2.1.0 | MIT | https://github.com/weisJ/jsvg |
| Gradle Tooling API | 8.14.3 | Apache-2.0 | https://docs.gradle.org |
| GraalVM Native Build Tools (Gradle plugin, build-time only) | 1.1.0 | Apache-2.0 | https://github.com/graalvm/native-build-tools |
| JUnit | 6.0.0 | EPL-2.0 | https://junit.org |
| Source Han Sans SC Bold (思源黑体, bundled font asset) | — | SIL Open Font License 1.1 — see [`javelin-tool/src/main/resources/front/OFL.txt`](javelin-tool/src/main/resources/front/OFL.txt) | https://github.com/adobe-fonts/source-han-sans |

Notes:

- **Source Han Sans SC Bold** is distributed unmodified (Reserved Font Name
  "Source"); the OFL 1.1 license text and copyright notice are kept
  next to the font file. The font is rasterized offline into the engine's
  built-in debug-text bitmap library (16x16, 1bpp).
- **GraalVM native-image / jlink** are used as build-time packaging tools and
  do not become part of distributed games beyond their outputs.
- Runtime transitive dependencies of the components above (e.g. the slf4j API
  used by Logback) are covered by the same licenses as their parent projects.
