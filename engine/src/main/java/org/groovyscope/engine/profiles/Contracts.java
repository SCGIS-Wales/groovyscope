/*
 * Copyright 2026 SCGIS-Wales and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.groovyscope.engine.profiles;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Diagnostic.Severity;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.practices.PracticeFinder;
import org.groovyscope.engine.practices.PracticeFinder.Practice;

/**
 * The contract surface of governed files, recorded once ({@code groovyscope baseline save}) and compared on every
 * check: class names, declared parameters, frozen method signatures, exception texts, output keys, and the test
 * seams tests stub through instance dispatch.
 */
public final class Contracts {

    /** Where the baseline lives, relative to the workspace root. */
    public static final String BASELINE = ".groovyscope/contract-baseline.json";
    static final Set<String> STUBBING = Set.of("when", "verify", "doReturn", "doNothing", "doThrow", "doAnswer");

    private Contracts() {
    }

    /** The methods tests stub, by name, from the modules matching the profile's test-source glob. */
    public static Set<String> seams(Profile profile, Path root, Map<String, ModuleNode> modules) {
        Set<String> names = new HashSet<>();
        Path top = root.toAbsolutePath().normalize();
        for (Map.Entry<String, ModuleNode> entry : modules.entrySet()) {
            Path file = org.groovyscope.engine.workspace.WorkspaceModel.toPath(entry.getKey());
            if (file == null || !file.toAbsolutePath().normalize().startsWith(top)
                    || !Profile.matches(profile.contract().testSources(), top.relativize(file.toAbsolutePath().normalize()))) {
                continue;
            }
            ModuleNode module = entry.getValue();
            for (ClassNode classNode : module.getClasses()) {
                new ClassCodeVisitorSupport() {
                    @Override
                    protected SourceUnit getSourceUnit() {
                        return module.getContext();
                    }

                    @Override
                    public void visitMethodCallExpression(MethodCallExpression call) {
                        // when(x).name(), verify(x).name(), doReturn(v).when(x).name()
                        if (call.getObjectExpression() instanceof MethodCallExpression stub
                                && STUBBING.contains(stub.getMethodAsString()) && call.getMethodAsString() != null) {
                            names.add(call.getMethodAsString());
                        }
                        // when(x.name(...))
                        if ("when".equals(call.getMethodAsString()) && call.getArguments() instanceof TupleExpression args
                                && args.getExpressions().size() == 1
                                && args.getExpression(0) instanceof MethodCallExpression inner && inner.getMethodAsString() != null) {
                            names.add(inner.getMethodAsString());
                        }
                        super.visitMethodCallExpression(call);
                    }
                }.visitClass(classNode);
            }
        }
        return names;
    }

    /** The contract items of one governed file, as {@code kind:name -> value}. */
    public static Map<String, String> extract(Profile profile, ModuleNode module, Descriptors.Document descriptor,
            Set<String> seams) {
        Map<String, String> items = new TreeMap<>();
        if (module == null) {
            return items;
        }
        if (descriptor != null) {
            for (Descriptors.Parameter parameter : descriptor.parameters().values()) {
                items.put("parameter:" + parameter.name(), parameter.type());
            }
        }
        for (ClassNode classNode : module.getClasses()) {
            if (classNode.isScript()) {
                continue;
            }
            if (!(classNode instanceof InnerClassNode)) {
                items.put("class:" + classNode.getName(), "class");
            }
            for (ConstructorNode constructor : classNode.getDeclaredConstructors()) {
                if (constructor.getLineNumber() > 0) {
                    items.put("constructor:" + classNode.getNameWithoutPackage() + parameters(constructor), "constructor");
                }
            }
            for (MethodNode method : classNode.getMethods()) {
                if (method.getLineNumber() <= 0) {
                    continue;
                }
                if (profile.contract().frozenMethods().contains(method.getName()) || overrides(method)) {
                    items.put("method:" + method.getName(), method.getName() + parameters(method) + ":"
                            + method.getReturnType().getNameWithoutPackage());
                }
                if (seams.contains(method.getName())) {
                    items.put("seam:" + method.getName(), visibility(method));
                }
            }
            new ClassCodeVisitorSupport() {
                @Override
                protected SourceUnit getSourceUnit() {
                    return module.getContext();
                }

                @Override
                public void visitThrowStatement(ThrowStatement statement) {
                    if (statement.getExpression() instanceof ConstructorCallExpression created
                            && profile.contract().exceptions().contains(created.getType().getNameWithoutPackage())
                            && created.getArguments() instanceof TupleExpression args && !args.getExpressions().isEmpty()) {
                        String text = literal(args.getExpression(0));
                        if (text != null) {
                            items.put("exception:" + text, created.getType().getNameWithoutPackage());
                        }
                    }
                    super.visitThrowStatement(statement);
                }

                @Override
                public void visitMethodCallExpression(MethodCallExpression call) {
                    if ("put".equals(call.getMethodAsString()) && profile.contract().outputCalls().contains(
                            receiverName(call.getObjectExpression())) && call.getArguments() instanceof TupleExpression args
                            && !args.getExpressions().isEmpty() && literal(args.getExpression(0)) != null) {
                        items.put("output:" + literal(args.getExpression(0)), "output");
                    }
                    super.visitMethodCallExpression(call);
                }

                @Override
                public void visitBinaryExpression(BinaryExpression expression) {
                    if (expression.getOperation().getType() == Types.ASSIGN
                            && expression.getLeftExpression() instanceof BinaryExpression index
                            && index.getOperation().getType() == Types.LEFT_SQUARE_BRACKET
                            && profile.contract().outputCalls().contains(receiverName(index.getLeftExpression()))
                            && literal(index.getRightExpression()) != null) {
                        items.put("output:" + literal(index.getRightExpression()), "output");
                    }
                    super.visitBinaryExpression(expression);
                }
            }.visitClass(classNode);
        }
        return items;
    }

    static boolean overrides(MethodNode method) {
        for (AnnotationNode annotation : method.getAnnotations()) {
            if (annotation.getClassNode().getNameWithoutPackage().equals("Override")) {
                return true;
            }
        }
        return false;
    }

    static String parameters(MethodNode method) {
        List<String> types = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            types.add(parameter.getOriginType().getNameWithoutPackage());
        }
        return "(" + String.join(",", types) + ")";
    }

    static String visibility(MethodNode method) {
        int modifiers = method.getModifiers();
        String access = Modifier.isPrivate(modifiers) ? "private" : Modifier.isProtected(modifiers) ? "protected"
                : Modifier.isPublic(modifiers) ? "public" : "package";
        return access + (method.isStatic() ? " static" : "");
    }

    static String literal(Expression expression) {
        if (expression instanceof ConstantExpression constant && constant.getValue() instanceof String text) {
            return text;
        }
        if (expression instanceof GStringExpression gstring) {
            return gstring.getText();
        }
        return null;
    }

    static String receiverName(Expression receiver) {
        if (receiver instanceof VariableExpression variable) {
            return variable.getName();
        }
        if (receiver instanceof PropertyExpression property) {
            return property.getPropertyAsString();
        }
        if (receiver instanceof MethodCallExpression call) {
            return call.getMethodAsString();
        }
        return null;
    }

    /** The recorded baseline for a workspace-relative path, or {@code null} when none was saved. */
    public static Map<String, String> baseline(Path root, String relativePath) {
        Path file = root.resolve(BASELINE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonObject files = parsed.isJsonObject() ? parsed.getAsJsonObject().getAsJsonObject("files") : null;
            JsonObject items = files == null ? null : files.getAsJsonObject(relativePath.replace('\\', '/'));
            if (items == null) {
                return null;
            }
            Map<String, String> result = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
            return result;
        } catch (IOException | JsonParseException | IllegalStateException | ClassCastException e) {
            return null;
        }
    }

    /** RP020 / RP021 findings: recorded items that are gone or changed. Additions are not findings. */
    public static List<Practice> drift(String uri, Map<String, String> baseline, Map<String, String> current,
            EngineConfig config) {
        List<Practice> out = new ArrayList<>();
        if (baseline == null) {
            return out;
        }
        for (Map.Entry<String, String> recorded : baseline.entrySet()) {
            String key = recorded.getKey();
            String now = current.get(key);
            if (recorded.getValue().equals(now)) {
                continue;
            }
            boolean seam = key.startsWith("seam:");
            String code = seam ? "RP021" : "RP020";
            Severity severity = PracticeFinder.severity(code, config);
            if (severity == null) {
                continue;
            }
            String message = seam
                    ? "test seam " + key.substring(5) + " was " + recorded.getValue() + ", now " + (now == null ? "gone" : now)
                    : (now == null ? "recorded contract item is gone: " : "recorded contract item changed: ") + key
                            + (now == null ? "" : " was " + recorded.getValue() + ", now " + now);
            out.add(new Practice(new Diagnostic(uri, Range.of(0, 0, 0, 1), severity, ProfileFinder.SOURCE, code, message),
                    null, List.of()));
        }
        return out;
    }
}
