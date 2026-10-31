package cn.donting.javelin.ast.processor;

import cn.donting.javelin.ast.annotation.Reflective;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.type.DeclaredType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link Reflective} 注解处理器。
 *
 * <p>编译时扫描所有带 {@link Reflective} 注解的类，生成注册类 {@code ReflectiveRegistration}：
 * 类加载时自动调用 {@code ReflectiveFactory.register()} 将标记类注册到工厂，实现零反射
 * 的对象创建。</p>
 *
 * <p>同时生成 native-image 反射配置 {@code reflect-config.json}（注册类 + 全部注册类，
 * 构造器级，2026-10-13）：注册体系本身零反射（类字面量+构造器引用，静态可达），但
 * {@code ReflectiveFactory} 静态块经 {@code Class.forName} 触发注册类自注册、且须为
 * 将来任何构造器反射路径兜底。core 分支与 game 分支（用户项目）各写一份到
 * CLASS_OUTPUT（随 jar 打包），路径用模块子目录——native-image 扫描
 * {@code META-INF/native-image/**} 全量合并、fat-jar 同名不冲突。</p>
 *
 * @author javelin
 * @since 1.0
 */
@SupportedAnnotationTypes("cn.donting.javelin.ast.annotation.Reflective")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class ReflectiveProcessor extends AbstractProcessor {

    /**
     * 生成注册类的全限定名。
     */
    private static final String GENERATED_CLASS_NAME = "cn.donting.javelin.core.reflective.ReflectiveRegistration";
    private static final String GENERATED_GAME_CLASS_NAME = "cn.donting.javelin.game.reflective.ReflectiveRegistration";

    /**
     * 工厂类的全限定名。
     */
    private static final String FACTORY_CLASS_NAME = "cn.donting.javelin.core.reflective.ReflectiveFactory";

    /**
     * native-image 反射配置输出路径（core 分支）。模块子目录防 fat-jar 同名冲突——
     * native-image 按 {@code META-INF/native-image/**} 全量合并。
     */
    private static final String CORE_REFLECT_CONFIG_PATH =
            "META-INF/native-image/cn.donting.javelin/javelin-core/reflect-config.json";

    /**
     * native-image 反射配置输出路径（game 分支——用户项目编译，随用户 jar 打包）。
     */
    private static final String GAME_REFLECT_CONFIG_PATH =
            "META-INF/native-image/cn.donting.javelin/game/reflect-config.json";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return true;
        }
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(Reflective.class);
        if (elements.isEmpty()) {
            return true;
        }
        Map<String, String> options = processingEnv.getOptions();
        String type = options.get("reflective.type");
        if (type != null && type.equals("core")) {
            processCore(elements, roundEnv);
        } else {
            processGame(elements, roundEnv);
        }
        return true;
    }

    private void processGame(Set<? extends Element> elements, RoundEnvironment roundEnv) {
        generateRegistrationClass(GENERATED_GAME_CLASS_NAME, elements);
        generateReflectConfig(GAME_REFLECT_CONFIG_PATH, GENERATED_GAME_CLASS_NAME, elements);
    }

    private void processCore(Set<? extends Element> elements, RoundEnvironment roundEnv) {
        generateRegistrationClass(GENERATED_CLASS_NAME, elements);
        generateReflectConfig(CORE_REFLECT_CONFIG_PATH, GENERATED_CLASS_NAME, elements);
    }

    /**
     * 生成反射工厂注册类。
     *
     * @param qualifiedClassName 生成类的全限定名
     * @param elements           需要注册的注解元素集合
     */
    private void generateRegistrationClass(String qualifiedClassName, Set<? extends Element> elements) {
        int lastDot = qualifiedClassName.lastIndexOf('.');
        String packageName = qualifiedClassName.substring(0, lastDot);
        String simpleClassName = qualifiedClassName.substring(lastDot + 1);

        try {
            JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(qualifiedClassName);
            try (Writer writer = sourceFile.openWriter();
                 PrintWriter pw = new PrintWriter(writer)) {

                pw.println("package " + packageName + ";");
                pw.println();
                pw.println("/**");
                pw.println(" * 编译时生成的反射工厂注册类。");
                pw.println(" *");
                pw.println(" * <p>由 {@link cn.donting.javelin.ast.processor.ReflectiveProcessor} 自动生成，");
                pw.println(" * <b>禁止手动修改</b>。</p>");
                pw.println(" *");
                pw.println(" * @author ReflectiveProcessor");
                pw.println(" * @since 1.0");
                pw.println(" */");
                pw.println("public class " + simpleClassName + " {");
                pw.println();
                pw.println("    static {");

                for (Element element : elements) {
                    TypeElement typeElement = (TypeElement) element;
                    String qualifiedName = typeElement.getQualifiedName().toString();
                    String binaryName = processingEnv.getElementUtils().getBinaryName(typeElement).toString();

                    boolean isInterface = typeElement.getKind() == ElementKind.INTERFACE;
                    boolean isAbstract = typeElement.getModifiers().contains(Modifier.ABSTRACT);
                    boolean isEnum = typeElement.getKind() == ElementKind.ENUM;

                    String supplier;
                    if (isEnum) {
                        supplier = "() -> { throw new UnsupportedOperationException(\"无法实例化枚举: " + qualifiedName + "\"); }";
                    } else if (isInterface || isAbstract) {
                        supplier = "() -> { throw new UnsupportedOperationException(\"无法实例化"
                                + (isInterface ? "接口" : "抽象类") + ": " + qualifiedName + "\"); }";
                    } else {
                        supplier = qualifiedName + "::new";
                    }

                    pw.println("        " + FACTORY_CLASS_NAME + ".register(\""
                            + binaryName + "\", " + qualifiedName + ".class, " + supplier + ", "
                            + qualifiedName + "[]::new);");

                    for (AnnotationMirror mirror : typeElement.getAnnotationMirrors()) {
                        DeclaredType declaredType = (DeclaredType) mirror.getAnnotationType();
                        TypeElement annotationElement = (TypeElement) declaredType.asElement();
                        Retention retention = annotationElement.getAnnotation(Retention.class);
                        if (retention != null && retention.value() == RetentionPolicy.RUNTIME) {
                            String annotationName = annotationElement.getQualifiedName().toString();
                            pw.println("        " + FACTORY_CLASS_NAME + ".registerAnnotation(\""
                                    + binaryName + "\", \"" + annotationName + "\");");
                        }
                    }
                }

                pw.println("    }");
                pw.println();
                pw.println("    private " + simpleClassName + "() {");
                pw.println("        // 禁止实例化");
                pw.println("    }");
                pw.println("}");
            }
        } catch (IOException e) {
            processingEnv.getMessager().printError(
                    "生成 " + qualifiedClassName + " 失败: " + e.getMessage());
        }
    }

    /**
     * 生成 native-image 反射配置（reflect-config.json）。
     *
     * <p>条目=注册类自身（{@code ReflectiveFactory} 静态块 {@code Class.forName} 的
     * 目标——关键条目）+ 全部注册类，统一构造器级（allDeclared/allPublic）兜底将来的
     * 构造器反射路径。类名取二进制名（运行时 {@code Class.forName} 口径，嵌套类
     * {@code $} 分隔）；排序后输出，产物 diff 友好。</p>
     *
     * @param configPath            配置输出路径（相对 CLASS_OUTPUT 根）
     * @param registrationClassName 注册类全限定名
     * @param elements              需要注册的注解元素集合
     */
    private void generateReflectConfig(String configPath, String registrationClassName,
                                        Set<? extends Element> elements) {
        List<String> names = new ArrayList<>();
        names.add(registrationClassName);
        for (Element element : elements) {
            names.add(processingEnv.getElementUtils().getBinaryName((TypeElement) element).toString());
        }
        Collections.sort(names);

        try {
            FileObject resource = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT, "", configPath);
            try (Writer writer = resource.openWriter();
                 PrintWriter pw = new PrintWriter(writer)) {
                pw.println("[");
                for (int i = 0; i < names.size(); i++) {
                    pw.println("  {\"name\": \"" + names.get(i)
                            + "\", \"allDeclaredConstructors\": true, \"allPublicConstructors\": true}"
                            + (i < names.size() - 1 ? "," : ""));
                }
                pw.println("]");
            }
        } catch (IOException e) {
            processingEnv.getMessager().printError(
                    "生成 " + configPath + " 失败: " + e.getMessage());
        }
    }
}
