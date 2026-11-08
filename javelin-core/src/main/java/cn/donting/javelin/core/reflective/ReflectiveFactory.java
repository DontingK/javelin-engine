package cn.donting.javelin.core.reflective;

import cn.donting.javelin.ast.annotation.Reflective;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * 反射工厂。
 *
 * <p>提供零反射的对象和数组创建能力。所有被 {@link Reflective}
 * 标记的类会在编译时自动注册到本工厂中。</p>
 *
 * <p>使用示例：</p>
 * <pre>
 * // 创建单个对象
 * PlayerData data = ReflectiveFactory.newInstance("com.mygame.PlayerData");
 *
 * // 获取 Class
 * Class&lt;?&gt; clazz = ReflectiveFactory.getRegisteredClass("com.mygame.PlayerData");
 *
 * // 创建数组
 * PlayerData[] arr = (PlayerData[]) ReflectiveFactory.newArray("com.mygame.PlayerData", 10);
 * </pre>
 *
 * @author javelin
 * @since 1.0
 */
public class ReflectiveFactory {

    /**
     * 注册条目。
     *
     * <p>同时持有 Class 对象、对象工厂和数组工厂，支持零反射创建。</p>
     */
    private static final class RegistryEntry {

        /**
         * 类对象。
         */
        final Class<?> clazz;

        /**
         * 对象工厂方法。
         */
        final Supplier<?> supplier;

        /**
         * 数组工厂方法。
         */
        final IntFunction<?> arraySupplier;

        /**
         * 构造注册条目。
         *
         * @param clazz          类对象
         * @param supplier       对象工厂方法
         * @param arraySupplier  数组工厂方法
         */
        RegistryEntry(Class<?> clazz, Supplier<?> supplier, IntFunction<?> arraySupplier) {
            this.clazz = clazz;
            this.supplier = supplier;
            this.arraySupplier = arraySupplier;
        }
    }

    /**
     * 类名到注册条目的映射。
     */
    private static final Map<String, RegistryEntry> CLASS_MAP = new ConcurrentHashMap<>();

    /**
     * 类名到该类运行时注解名称集合的映射。
     *
     * <p>由编译时生成的 {@code ReflectiveRegistration} 填充，用于在 Native Image 等
     * 零反射场景下判断类是否带有某个注解。</p>
     */
    private static final Map<String, Set<String>> ANNOTATION_MAP = new ConcurrentHashMap<>();

    /**
     * 运行时注解的简单属性值：类名 → 注解类名 → 属性名 → 属性值（字符串形式）。
     *
     * <p>同由生成的注册类填充，{@link #getAnnotationValue} 读取；属性值取自
     * {@code getElementValuesWithDefaults}，注解未显式写的属性也有默认值条目。
     * native-image 默认不把注解带进镜像，本表是 core 侧读注解值的唯一 AOT 安全
     * 通道（禁止 {@code clazz.getAnnotation(...)}）。支持 boolean/int/long/float/
     * double/char/String 与枚举常量，数组/Class/嵌套注解不入表。</p>
     */
    private static final Map<String, Map<String, Map<String, String>>> ANNOTATION_VALUE_MAP =
            new ConcurrentHashMap<>();

    static {
        try {
            Class.forName("cn.donting.javelin.core.reflective.ReflectiveRegistration");

        } catch (ClassNotFoundException ignored) {
            // ReflectiveRegistration 未生成（没有类带 @Reflective 注解）
        } try {
            Class.forName("cn.donting.javelin.game.reflective.ReflectiveRegistration");
        } catch (ClassNotFoundException ignored) {
            // ReflectiveRegistration 未生成（没有类带 @Reflective 注解）
        }
    }

    private ReflectiveFactory() {
        // 禁止实例化
    }

    /**
     * 注册类。
     *
     * <p>由编译时生成的 {@code ReflectiveRegistration} 自动调用，不建议手动调用。</p>
     *
     * @param className      类全限定名
     * @param clazz          类对象
     * @param supplier       对象工厂方法
     * @param arraySupplier  数组工厂方法
     */
    public static void register(String className, Class<?> clazz,
                                Supplier<?> supplier, IntFunction<?> arraySupplier) {
        CLASS_MAP.put(className, new RegistryEntry(clazz, supplier, arraySupplier));
    }

    /**
     * 注册类的运行时注解信息。
     *
     * <p>由编译时生成的 {@code ReflectiveRegistration} 自动调用，不建议手动调用。</p>
     *
     * @param className            类全限定名
     * @param annotationClassName  注解类全限定名
     */
    public static void registerAnnotation(String className, String annotationClassName) {
        ANNOTATION_MAP.computeIfAbsent(className, k -> ConcurrentHashMap.newKeySet())
                .add(annotationClassName);
    }

    /**
     * 注册运行时注解的简单属性值。
     *
     * <p>由编译时生成的 {@code ReflectiveRegistration} 自动调用，不建议手动调用。</p>
     *
     * @param className            类全限定名
     * @param annotationClassName  注解类全限定名
     * @param attribute            属性名
     * @param value                属性值（字符串形式，读取侧自行解析）
     */
    public static void registerAnnotationValue(String className, String annotationClassName,
                                               String attribute, String value) {
        ANNOTATION_VALUE_MAP
                .computeIfAbsent(className, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(annotationClassName, k -> new ConcurrentHashMap<>())
                .put(attribute, value);
    }

    /**
     * 根据类对象创建实例。
     *
     * @param clazz 类对象
     * @param <T>   类型参数
     * @return 实例
     * @throws IllegalArgumentException 如果类未注册
     */
    public static <T> T newInstance(Class<T> clazz) {
        return newInstance(clazz.getName());
    }

    /**
     * 根据类全限定名创建实例。
     *
     * @param className 类全限定名
     * @param <T>       类型参数
     * @return 实例
     * @throws IllegalArgumentException 如果类未注册
     */
    public static <T> T newInstance(String className) {
        RegistryEntry entry = CLASS_MAP.get(className);
        if (entry == null) {
            throw new IllegalArgumentException("未注册的类: " + className);
        }
        return (T) entry.supplier.get();
    }

    /**
     * 获取已注册的类对象。
     *
     * @param className 类全限定名
     * @return 类对象
     * @throws IllegalArgumentException 如果类未注册
     */
    public static Class<?> getRegisteredClass(String className) {
        RegistryEntry entry = CLASS_MAP.get(className);
        if (entry == null) {
            throw new IllegalArgumentException("未注册的类: " + className);
        }
        return entry.clazz;
    }

    /**
     * 创建数组。
     *
     * <p>通过预注册的数组工厂方法创建，零反射。</p>
     *
     * @param componentClassName 组件类全限定名
     * @param length             数组长度
     * @return 数组对象
     * @throws IllegalArgumentException 如果组件类未注册
     */
    public static Object newArray(String componentClassName, int length) {
        RegistryEntry entry = CLASS_MAP.get(componentClassName);
        if (entry == null) {
            throw new IllegalArgumentException("未注册的类: " + componentClassName);
        }
        return entry.arraySupplier.apply(length);
    }

    /**
     * 检查类是否已注册。
     *
     * @param className 类全限定名
     * @return true 如果已注册
     */
    public static boolean isRegistered(String className) {
        return CLASS_MAP.containsKey(className);
    }

    /**
     * 已注册类对象的快照（无序；零反射、AOT 安全）。
     *
     * <p>供上层按类型筛选取用（编辑器「添加组件」列 Component 子类等），返回新
     * 列表、修改不影响注册表。</p>
     *
     * @return 已注册类对象列表
     */
    public static List<Class<?>> registeredClasses() {
        List<Class<?>> classes = new ArrayList<>(CLASS_MAP.size());
        for (RegistryEntry entry : CLASS_MAP.values()) {
            classes.add(entry.clazz);
        }
        return classes;
    }


    /**
     * 检查已注册类是否带有指定的运行时注解。
     *
     * <p>仅对已被 {@link Reflective} 注册过的类有效；
     * 未注册的类始终返回 {@code false}。</p>
     *
     * @param clazz            目标类
     * @param annotationClass  注解类
     * @return true 如果该类带有指定注解
     */
    public static boolean isAnnotation(Class<?> clazz, Class<? extends Annotation> annotationClass) {
        if (clazz == null || annotationClass == null) {
            return false;
        }
        Set<String> annotations = ANNOTATION_MAP.get(clazz.getName());
        return annotations != null && annotations.contains(annotationClass.getName());
    }

    /**
     * 读已注册类上指定 RUNTIME 注解的简单属性值（字符串形式）。
     *
     * <p>零反射、AOT 安全；类未注册、注解未注册或属性不在表内（含数组/Class/
     * 嵌套注解这类不入表的类型）一律返回 {@code null}。</p>
     *
     * @param clazz            目标类
     * @param annotationClass  注解类
     * @param attribute        属性名
     * @return 属性值字符串；无则 null
     */
    public static String getAnnotationValue(Class<?> clazz, Class<? extends Annotation> annotationClass,
                                            String attribute) {
        if (clazz == null || annotationClass == null || attribute == null) {
            return null;
        }
        Map<String, Map<String, String>> byAnnotation = ANNOTATION_VALUE_MAP.get(clazz.getName());
        if (byAnnotation == null) {
            return null;
        }
        Map<String, String> values = byAnnotation.get(annotationClass.getName());
        return values == null ? null : values.get(attribute);
    }

    /**
     * 读布尔型注解属性（{@link #getAnnotationValue} 的解析快捷口）。
     *
     * @param clazz            目标类
     * @param annotationClass  注解类
     * @param attribute        属性名
     * @return 属性值；缺失/不可解析 → false（口径同 {@link Boolean#parseBoolean}）
     */
    public static boolean getAnnotationBoolean(Class<?> clazz, Class<? extends Annotation> annotationClass,
                                               String attribute) {
        return Boolean.parseBoolean(getAnnotationValue(clazz, annotationClass, attribute));
    }

    /**
     * 读整型注解属性（{@link #getAnnotationValue} 的解析快捷口）。
     *
     * @param clazz            目标类
     * @param annotationClass  注解类
     * @param attribute        属性名
     * @param defaultValue     缺失/不可解析时的返回值
     * @return 属性值；缺失/不可解析 → defaultValue
     */
    public static int getAnnotationInt(Class<?> clazz, Class<? extends Annotation> annotationClass,
                                       String attribute, int defaultValue) {
        String value = getAnnotationValue(clazz, annotationClass, attribute);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
