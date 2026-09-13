package com.javelin.ast.processor;

import com.javelin.ast.annotation.Reflective;

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
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.Map;
import java.util.Set;

/**
 * {@link Reflective} 注解处理器。
 *
 * <p>编译时扫描所有带 {@link Reflective} 注解的类，生成注册代码到 {@code TypeRegistration}。</p>
 *
 * <p>生成的 {@code TypeRegistration} 在类加载时自动调用 {@code ReflectiveFactory.register()}，
 * 将标记类注册到工厂中，实现零反射的对象创建。</p>
 *
 * @author javelin
 * @since 1.0
 */
@SupportedAnnotationTypes("com.javelin.ast.annotation.Reflective")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class ReflectiveProcessor extends AbstractProcessor {

    /**
     * 生成注册类的全限定名。
     */
    private static final String GENERATED_CLASS_NAME = "com.javelin.core.reflective.ReflectiveRegistration";
    private static final String GENERATED_GAME_CLASS_NAME = "com.javelin.game.reflective.ReflectiveRegistration";

    /**
     * 工厂类的全限定名。
     */
    private static final String FACTORY_CLASS_NAME = "com.javelin.core.reflective.ReflectiveFactory";

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
    }

    private void processCore(Set<? extends Element> elements, RoundEnvironment roundEnv) {
        generateRegistrationClass(GENERATED_CLASS_NAME, elements);
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
                pw.println(" * <p>由 {@link com.javelin.ast.processor.ReflectiveProcessor} 自动生成，");
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
}
