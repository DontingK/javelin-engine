package cn.donting.javelin.editor.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 类反射扫描工具类。
 *
 * <p>提供基于类路径的包扫描能力，支持文件系统目录和 JAR 包，
 * 用于运行时自动发现指定包下的类。</p>
 *
 * @author javelin
 * @since 1.0
 */
public final class ClassUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClassUtil.class);

    private ClassUtil() {
        // 工具类禁止实例化
    }

    /**
     * 扫描指定包下的所有类。
     *
     * <p>最基础的扫描方法，不做任何过滤，同时支持文件系统路径和 JAR 包。</p>
     *
     * @param packageName 包名，如 {@code "cn.donting.javelin.editor.menu"}
     * @return 扫描到的类列表
     */
    public static List<Class<?>> scanClasses(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return scanClassesRecursive(packageName);
        }
        List<Class<?>> classes = new ArrayList<>();
        try {
            ClassLoader cl = ClassUtil.class.getClassLoader();
            String path = packageName.replace('.', '/');
            Enumeration<URL> resources = cl.getResources(path);
            List<URL> resourceList = new ArrayList<>();
            while (resources.hasMoreElements()) {
                resourceList.add(resources.nextElement());
            }
            LOGGER.info("扫描包 [{}]: classLoader={}, 找到 {} 个资源 URL",
                    packageName, cl, resourceList.size());
            for (URL resource : resourceList) {
                if ("file".equals(resource.getProtocol())) {
                    scanFile(resource, packageName, path, classes);
                } else if ("jar".equals(resource.getProtocol())) {
                    scanJar(resource, packageName, path, classes);
                }
            }
        } catch (Exception e) {
            LOGGER.error("扫描包 [{}] 失败", packageName, e);
        }
        return classes;
    }

    /**
     * 扫描指定包下所有继承（或实现）指定基础类的非抽象类。
     *
     * @param packageName 包名
     * @param baseClass   基础类或接口
     * @return 过滤后的类列表
     */
    public static List<Class<?>> scanClasses(String packageName, Class<?> baseClass) {
        return scanClasses(packageName, baseClass, null);
    }

    /**
     * 扫描指定包下所有符合条件的非抽象类。
     *
     * <p>先调用 {@link #scanClasses(String)} 获取包下全部类，再在内存中按
     * {@code baseClass} 和 {@code annotation} 条件过滤。</p>
     *
     * @param packageName 包名，如 {@code "cn.donting.javelin.editor.menu.hierarchy"}
     * @param baseClass   基础类或接口，为 {@code null} 时不过滤类型
     * @param annotation  目标注解类，为 {@code null} 时不校验注解
     * @return 过滤后的类列表
     */
    public static List<Class<?>> scanClasses(String packageName, Class<?> baseClass,
                                             Class<? extends Annotation> annotation) {
        if (packageName == null || packageName.isEmpty()) {
            return scanClassesRecursive(packageName, baseClass, annotation);
        }
        List<Class<?>> result = new ArrayList<>();
        for (Class<?> clazz : scanClasses(packageName)) {
            if (baseClass != null && !baseClass.isAssignableFrom(clazz)) {
                continue;
            }
            if (Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }
            if (annotation != null && clazz.getAnnotation(annotation) == null) {
                continue;
            }
            result.add(clazz);
        }
        return result;
    }

    /**
     * 递归扫描指定根包及其所有子包下的类。
     *
     * @param packageName 根包名，如 {@code "com.javelin.game"}
     * @return 扫描到的类列表
     */
    public static List<Class<?>> scanClassesRecursive(String packageName) {
        return scanClassesRecursive(packageName, null, null);
    }

    /**
     * 递归扫描指定根包及其所有子包下符合条件的非抽象类。
     *
     * @param packageName 根包名
     * @param baseClass   基础类或接口，为 {@code null} 时不过滤类型
     * @param annotation  目标注解类，为 {@code null} 时不校验注解
     * @return 过滤后的类列表
     */
    public static List<Class<?>> scanClassesRecursive(String packageName, Class<?> baseClass,
                                                      Class<? extends Annotation> annotation) {
        List<Class<?>> classes = new ArrayList<>();
        try {
            ClassLoader cl = ClassUtil.class.getClassLoader();
            String path = packageName.replace('.', '/');
            Enumeration<URL> resources = cl.getResources(path);
            List<URL> resourceList = new ArrayList<>();
            while (resources.hasMoreElements()) {
                resourceList.add(resources.nextElement());
            }
            LOGGER.info("递归扫描包 [{}]: classLoader={}, 找到 {} 个资源 URL",
                    packageName, cl, resourceList.size());
            for (URL resource : resourceList) {
                if ("file".equals(resource.getProtocol())) {
                    scanDirectory(decodeFile(resource), packageName, classes);
                } else if ("jar".equals(resource.getProtocol())) {
                    scanJarRecursive(resource, packageName, classes);
                }
            }
        } catch (Exception e) {
            LOGGER.error("递归扫描包 [{}] 失败", packageName, e);
        }
        return filterClasses(classes, baseClass, annotation);
    }

    /**
     * 递归扫描指定根包及其所有子包下，所有继承（或实现）指定基础类且带指定注解的非抽象类，并实例化。
     *
     * <p>底层调用 {@link #scanClassesRecursive(String, Class, Class)} 获取类列表后逐一实例化。</p>
     *
     * @param packageName 根包名
     * @param baseClass   基础类或接口
     * @param annotation  目标注解类
     * @param <T>         基础类类型参数
     * @return 扫描并实例化后的对象列表
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> scanInstances(String packageName, Class<T> baseClass,
                                            Class<? extends Annotation> annotation) {
        List<T> instances = new ArrayList<>();
        for (Class<?> clazz : scanClassesRecursive(packageName, baseClass, annotation)) {
            try {
                instances.add((T) clazz.getDeclaredConstructor().newInstance());
            } catch (Exception e) {
                LOGGER.error("实例化类 [{}] 失败", clazz.getName(), e);
            }
        }
        return instances;
    }

    /**
     * 按类型、抽象性、注解过滤类列表。
     *
     * @param classes     原始类列表
     * @param baseClass   基础类或接口
     * @param annotation  目标注解类
     * @return 过滤后的类列表
     */
    private static List<Class<?>> filterClasses(List<Class<?>> classes, Class<?> baseClass,
                                                Class<? extends Annotation> annotation) {
        List<Class<?>> result = new ArrayList<>();
        for (Class<?> clazz : classes) {
            if (baseClass != null && !baseClass.isAssignableFrom(clazz)) {
                continue;
            }
            if (Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }
            if (annotation != null && clazz.getAnnotation(annotation) == null) {
                continue;
            }
            result.add(clazz);
        }
        return result;
    }

    /**
     * 将 URL 解码为本地文件。处理路径中空格等字符被编码为 %20 的情况。
     *
     * @param resource URL 资源
     * @return 解码后的本地文件
     */
    private static File decodeFile(URL resource) {
        return new File(URLDecoder.decode(resource.getFile(), StandardCharsets.UTF_8));
    }

    /**
     * 扫描文件系统目录下的类文件。
     */
    private static void scanFile(URL resource, String packageName, String path,
                                 List<Class<?>> classes) {
        File dir = decodeFile(resource);
        if (!dir.exists()) {
            LOGGER.warn("scanFile 目录不存在: {}", dir);
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".class")) {
                continue;
            }
            String className = packageName + '.'
                    + file.getName().substring(0, file.getName().length() - 6);
            try {
                classes.add(Class.forName(className));
            } catch (Exception e) {
                LOGGER.error("加载类 [{}] 失败", className, e);
            }
        }
    }

    /**
     * 递归扫描文件系统目录下的类文件。
     *
     * @param dir         当前目录
     * @param packageName 当前包名
     * @param classes     收集到的类列表
     */
    private static void scanDirectory(File dir, String packageName, List<Class<?>> classes) {
        if (!dir.exists()) {
            LOGGER.warn("扫描目录不存在: {}", dir);
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            LOGGER.warn("扫描目录无法读取: {}", dir);
            return;
        }
        int classCount = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                String childPackage = packageName.isEmpty()
                        ? file.getName()
                        : packageName + '.' + file.getName();
                scanDirectory(file, childPackage, classes);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName.isEmpty()
                        ? file.getName().substring(0, file.getName().length() - 6)
                        : packageName + '.' + file.getName().substring(0, file.getName().length() - 6);
                try {
                    Class<?> clazz = Class.forName(className);
                    classes.add(clazz);
                    classCount++;
                } catch (Exception e) {
                    LOGGER.error("加载类 [{}] 失败", className, e);
                }
            }
        }
//        LOGGER.debug("扫描目录完成: dir={}, packageName={}, 加载 {} 个类", dir, packageName, classCount);
    }

    /**
     * 扫描 JAR 包内的类文件。
     */
    private static void scanJar(URL resource, String packageName, String path,
                                List<Class<?>> classes) {
        LOGGER.info("扫描 JAR 包: resource={}", resource);
        try {
            JarURLConnection jarConn = (JarURLConnection) resource.openConnection();
            JarFile jarFile = jarConn.getJarFile();
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(path + '/') || !name.endsWith(".class")) {
                    continue;
                }
                // 仅扫描直接子包，跳过更深层的子目录
                String relative = name.substring(path.length() + 1);
                if (relative.contains("/")) {
                    continue;
                }
                String className = packageName + '.'
                        + relative.substring(0, relative.length() - 6);
                try {
                    classes.add(Class.forName(className));
                } catch (Exception e) {
                    LOGGER.error("加载类 [{}] 失败", className, e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("扫描 JAR 资源 [{}] 失败", resource, e);
        }
    }

    /**
     * 递归扫描 JAR 包内指定根包及其所有子包下的类文件。
     *
     * @param resource    JAR 资源 URL
     * @param packageName 根包名
     * @param classes     收集到的类列表
     */
    private static void scanJarRecursive(URL resource, String packageName, List<Class<?>> classes) {
        try {
            JarURLConnection jarConn = (JarURLConnection) resource.openConnection();
            JarFile jarFile = jarConn.getJarFile();
            String path = packageName.replace('.', '/');
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(path + '/') || !name.endsWith(".class")) {
                    continue;
                }
                String relative = name.substring(path.length() + 1, name.length() - 6);
                if (relative.isEmpty()) {
                    continue;
                }
                String className = packageName.isEmpty()
                        ? relative.replace('/', '.')
                        : packageName + '.' + relative.replace('/', '.');
                try {
                    classes.add(Class.forName(className));
                } catch (Exception e) {
                    LOGGER.error("加载类 [{}] 失败", className, e);
                }
            }
        } catch (Exception e) {
            LOGGER.error("递归扫描 JAR 资源 [{}] 失败", resource, e);
        }
    }

    /**
     * 获取当前类 + 所有父类 全部私有字段
     * @param clazz 目标类
     * @return 私有字段集合
     */
    public static List<Field> getAllFields(Class<?> clazz) {
        List<Field> privateFields = new ArrayList<>();
        Class<?> current = clazz;

        // 递归向上遍历所有父类，直到 Object
        while (current != Object.class) {
            Field[] declaredFields = current.getDeclaredFields();
            // 判断是否私有
            privateFields.addAll(Arrays.asList(declaredFields));
            // 切换为父类
            current = current.getSuperclass();
        }
        return privateFields;
    }

}
