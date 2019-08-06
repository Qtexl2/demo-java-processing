package processing.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.Name;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import processing.annotation.WebSocketController;
import processing.annotation.WebSocketHandler;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SupportedAnnotationTypes({"processing.annotation.WebSocketController", "processing.annotation.WebSocketHandler", "org.springframework.context.annotation.Configuration"})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class WebSocketControllerProcessor extends AbstractProcessor {

    private static final String objectMapperName = "objectMapper";
    private static final String wsSession = "wsSession";
    private static final String message = "message";
    private static final String NAME_VAR_REGISTRY = "registry";

    private String basePackage;

    Filer filer;
    TypeSpec configFile;
    Types typeUtils;
    Elements elementUtils;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        filer = processingEnv.getFiler();
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
    }


    private void findConfigPackage(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(Configuration.class)) {
            basePackage = elementUtils.getPackageOf(element).toString();
            break;
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        findConfigPackage(roundEnv);

        List<FieldSpec> fields = new ArrayList<>();

        MethodSpec.Builder configClassConstructorBuilder = MethodSpec.constructorBuilder();
        CodeBlock.Builder configClassBodyConstructorBuilder = CodeBlock.builder();
        String configFilePackage = "";

        for (Element element : roundEnv.getElementsAnnotatedWith(WebSocketController.class)) {
            String currentPackage = elementUtils.getPackageOf(element).toString();
            configFilePackage = currentPackage;
            String nameField = getFieldName(element.getSimpleName().toString());
            String proxyType = createProxyType(element, nameField);
            ClassName type = ClassName.get(currentPackage, proxyType);

            FieldSpec filed = FieldSpec.builder(type, nameField)
                    .addModifiers(Modifier.PRIVATE)
                    .build();
            fields.add(filed);

            configClassConstructorBuilder.addParameter(
                    ParameterSpec.builder(type, nameField).build()
            );
            configClassConstructorBuilder.addCode(
                    CodeBlock.builder()
                            .addStatement("this." + nameField + " = " + nameField)
                            .build()
            );
            WebSocketController annotation = element.getAnnotation(WebSocketController.class);
            String url = annotation.url();
            String format = NAME_VAR_REGISTRY + ".addHandler(" + nameField + ", \"" + url + "\")";
            configClassBodyConstructorBuilder.addStatement(format);

        }
        if (configFile != null) {
            return true;
        }
        configFile = TypeSpec
                .classBuilder("WebSocketConfiguration")
                .addModifiers(Modifier.PUBLIC)
                .addFields(fields)
                .addMethod(MethodSpec
                        .methodBuilder("registerWebSocketHandlers")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(void.class)
                        .addParameter(WebSocketHandlerRegistry.class, registry)
                        .addCode(configClassBodyConstructorBuilder.build())
                        .build())
                .addAnnotation(Configuration.class)
                .addAnnotation(EnableWebSocket.class)
                .addMethod(configClassConstructorBuilder.build())
                .addSuperinterface(WebSocketConfigurer.class)
                .build();

        JavaFile javaFile = JavaFile
                .builder(configFilePackage, configFile)
                .indent("    ")
                .build();
        try {
            javaFile.writeTo(filer);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

    private String createProxyType(Element element, String fieldName) {
        PackageElement packageOf = elementUtils.getPackageOf(element);

        TypeName typeName = TypeName.get(element.asType());
        String proxyType = "Proxy" + element.getSimpleName().toString();
        TypeSpec wsConfiguration = TypeSpec
                .classBuilder(proxyType)
                .addModifiers(Modifier.PUBLIC)
                .addField(FieldSpec.builder(typeName, fieldName, Modifier.PRIVATE, Modifier.FINAL)
                        .build())
                .addField(FieldSpec.builder(ObjectMapper.class, objectMapperName, Modifier.PRIVATE, Modifier.FINAL)
                        .build())
                .addAnnotation(Component.class)
                .superclass(TextWebSocketHandler.class)
                .addMethod(createProxyTypeConstructor(element, fieldName, typeName))
                .addMethod(createProxySwitchMethod(element, fieldName))
                .build();

        JavaFile javaFile = JavaFile
                .builder(packageOf.toString(), wsConfiguration)
                .indent("    ")
                .build();
        try {
            javaFile.writeTo(filer);
            return proxyType;
        } catch (IOException e) {
            return null;
        }
    }

    private MethodSpec createProxyTypeConstructor(Element element, String fieldName, TypeName typeName) {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addParameter(ObjectMapper.class, objectMapperName)
                .addCode("this." + objectMapperName + " = " + objectMapperName + ";")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Autowired.class);

        StringBuilder code = new StringBuilder("this." + fieldName + " = new " + element.getSimpleName() + "(");
        for (Element enclosedElement : element.getEnclosedElements()) {
            if (enclosedElement.getKind() == ElementKind.CONSTRUCTOR) {
                com.sun.tools.javac.util.List<Symbol.VarSymbol> parameters = ((Symbol.MethodSymbol) enclosedElement).getParameters();
                for (Symbol.VarSymbol parameter : parameters) {
                    Type type = parameter.asType();
                    Name simpleName = parameter.getSimpleName();
                    builder.addParameter(TypeName.get(type), simpleName.toString());
                    code.append(simpleName.toString()).append(",");
                }
                if (parameters.size() != 0) {
                    code.deleteCharAt(code.length() - 1);
                }
                break;
            }
        }
        code.append(");");
        builder.addCode(code.toString());
        return builder.build();
    }

    //
    private MethodSpec createProxySwitchMethod(Element element, String fieldName) {
        WebSocketController controllerAnnotation = element.getAnnotation(WebSocketController.class);
        String key = controllerAnnotation.idKey();

        String json = "jsonNode";
        MethodSpec.Builder switchMethod = MethodSpec.methodBuilder("handleTextMessage")
                .addModifiers(Modifier.PROTECTED)
                .returns(void.class)
                .addParameter(WebSocketSession.class, wsSession)
                .addParameter(TextMessage.class, message)
                .addException(Exception.class)
                .addStatement("$T " + json + " = " + objectMapperName + ".readTree(" + message + ".getPayload())", JsonNode.class)
                .beginControlFlow("switch(" + json + ".get(\"" + key + "\").asText())");


        for (Element item : element.getEnclosedElements()) {
            if (item.getKind() == ElementKind.METHOD) {
                WebSocketHandler annotation = item.getAnnotation(WebSocketHandler.class);
                if (annotation != null) {
                    String value = annotation.idValue();
                    switchMethod.addCode("case \"" + value + "\":");
                    addInvokeReadyMethod(switchMethod, item, fieldName, value);
                    switchMethod.addStatement("break");
                }
            }
        }
        switchMethod.endControlFlow();
        return switchMethod.build();
    }

    private void addInvokeReadyMethod(MethodSpec.Builder builder, Element element, String fieldName, String value) {
        Symbol.MethodSymbol method = (Symbol.MethodSymbol) element;
        StringBuilder code = new StringBuilder(fieldName + "." + method.getSimpleName() + "(");
        System.out.println(method.getDefaultValue());
        System.out.println(method.getReturnType());
        com.sun.tools.javac.util.List<Symbol.VarSymbol> parameters = method.getParameters();
        for (Symbol.VarSymbol parameter : parameters) {
            Type type = parameter.asType();
            if (type.toString().equals("org.springframework.web.socket.WebSocketSession")) {
                code.append(wsSession);
            } else {
                String nameVariable = value + type.asElement().getSimpleName();
                System.out.println(nameVariable);
                builder.addStatement("$T " + nameVariable + " = " + objectMapperName + ".readValue(" + message + ".getPayload(), $T.class)", type, type);
//
                code.append(nameVariable);
            }
            code.append(",");
        }

        if (parameters.length() != 0) {
            code.deleteCharAt(code.length() - 1);
        }
        code.append(");");
        builder.addCode(code.toString());
    }

    private String getFieldName(String name) {
        char firstLetter = Character.toLowerCase(name.charAt(0));
        return firstLetter + name.substring(1);
    }
}
