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
package org.groovyscope.engine.practices;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Diagnostic.Severity;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.semantics.Members;

/**
 * groovyscope's own practice diagnostics (docs/practices.md, section 16): the entry point that builds one
 * {@link PracticeContext} per document and runs every group on it ({@link CoreScan} for GS001 to GS012,
 * {@link TestDoubleFinder} for GS013 to GS016, {@link NamingFinder} for GS020 to GS027, {@link CommentChecks} for
 * GS060 to GS065, {@link TypeMixChecks} for GS070 to GS076, {@link DesignChecks} for GS090 to GS097, and the finders
 * of the later groups), plus the type helpers they share. Each one reports only when the tree says so for certain (a
 * statically known type, a literal); untyped code is left alone rather than guessed about. Where a mechanical fix is
 * safe, the finding carries it as text edits.
 */
public final class PracticeFinder {

    /** Reported as the diagnostic source. */
    public static final String SOURCE = "groovyscope";

    /** Another way to fix a finding, offered as a code action next to the first (never applied in bulk). */
    public record Alternative(String title, List<TextEdit> edits) {
        public Alternative {
            edits = List.copyOf(edits);
        }
    }

    /** A finding with an optional fix, and other fixes when the intent is not clear from the code. */
    public record Practice(Diagnostic diagnostic, String fixTitle, List<TextEdit> fix, List<Alternative> alternatives) {
        public Practice {
            fix = List.copyOf(fix);
            alternatives = List.copyOf(alternatives);
        }

        public Practice(Diagnostic diagnostic, String fixTitle, List<TextEdit> fix) {
            this(diagnostic, fixTitle, fix, List.of());
        }

        public boolean fixable() {
            return !fix.isEmpty();
        }

        /** Whether the fix keeps behaviour, so the bulk fix may apply it (see {@link PracticeCatalog#safety}). */
        public boolean preserving() {
            return PracticeCatalog.PRESERVING.equals(PracticeCatalog.bundled().safety(diagnostic.code()));
        }
    }

    private PracticeFinder() {
    }

    /** Practice findings for one Groovy document, its profile read from the path. */
    public static List<Practice> find(String uri, ModuleNode module, String text, EngineConfig config) {
        return find(uri, module, text, config, null);
    }

    /**
     * Practice findings for one Groovy document checked with lint profile {@code profile} ({@code null} to read it
     * from the path).
     */
    public static List<Practice> find(String uri, ModuleNode module, String text, EngineConfig config,
            String profile) {
        List<Practice> out = new ArrayList<>();
        if (module == null || text == null || !config.practices().enabled() || uri.endsWith(".java")) {
            return out;
        }
        PracticeContext context = PracticeContext.of(uri, module, text, config, profile, out);
        new CoreScan(context).visitModule();
        NamingFinder.find(context);
        TestDoubleFinder.find(context);
        SqlFinder.find(context);
        SecretFinder.find(context);
        PitfallFinder.find(context);
        JenkinsFinder.find(context);
        SpockFinder.find(context);
        TestBloatChecks.find(context);
        GradleFinder.find(context);
        CommentChecks.find(context);
        TypeMixChecks.find(context);
        DesignChecks.find(context);
        return out;
    }

    /**
     * Every value the local declared by {@code local} is assigned in {@code method} of {@code owner}, with its type
     * when known, when it is assigned more than once; empty otherwise. Hover shows it, so the mixing is seen before an
     * edit.
     */
    public static List<org.groovyscope.engine.model.Hover.InferredType> assignedTypes(ClassNode owner,
            MethodNode method, VariableExpression local) {
        return TypeMixChecks.assignedTypes(owner, method, local);
    }

    /** Severity for a code: the configured override, else the catalogue default; {@code null} when switched off. */
    public static Severity severity(String code, EngineConfig config) {
        String configured = config.practices().severityOf(code);
        String value = configured != null ? configured
                : PracticeCatalog.bundled().find(code).map(PracticeCatalog.Entry::severity).orElse("warning");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "off" -> null;
            case "error" -> Severity.ERROR;
            case "info", "information" -> Severity.INFORMATION;
            default -> Severity.WARNING;
        };
    }

    /** The statically known type of an expression, or {@code null} when it is dynamic or unknown. */
    public static ClassNode typeOf(Expression expression) {
        if (expression instanceof ConstantExpression constant) {
            return constant.getValue() == null ? null : constant.getType();
        }
        if (expression instanceof GStringExpression) {
            return ClassHelper.GSTRING_TYPE;
        }
        if (expression instanceof CastExpression cast) {
            return cast.getType();
        }
        if (expression instanceof ConstructorCallExpression constructor) {
            return constructor.getType();
        }
        if (expression instanceof FieldExpression field) {
            return field.getField().isDynamicTyped() ? null : field.getField().getOriginType();
        }
        if (expression instanceof VariableExpression variable) {
            if (variable.isThisExpression() || variable.isSuperExpression()) {
                return null;
            }
            Variable accessed = variable.getAccessedVariable();
            if (accessed == null || accessed.isDynamicTyped()) {
                return null;
            }
            return accessed.getOriginType();
        }
        if (expression instanceof PropertyExpression property && property.getPropertyAsString() != null) {
            ClassNode owner = ownerType(property.getObjectExpression());
            return owner == null ? null : memberType(Members.membersNamed(owner, property.getPropertyAsString()));
        }
        if (expression instanceof MethodCallExpression call && call.getMethodAsString() != null) {
            ClassNode owner = ownerType(call.getObjectExpression());
            return owner == null ? null : returnType(Members.accepting(Members.methodsNamed(owner,
                    call.getMethodAsString()), Members.argumentCount(call.getArguments())));
        }
        if (expression instanceof StaticMethodCallExpression call) {
            return returnType(Members.accepting(Members.methodsNamed(call.getOwnerType(), call.getMethod()),
                    Members.argumentCount(call.getArguments())));
        }
        return null;
    }

    private static ClassNode ownerType(Expression receiver) {
        if (receiver instanceof ClassExpression classExpression) {
            return classExpression.getType();
        }
        ClassNode type = typeOf(receiver);
        return type == null || ClassHelper.isObjectType(type) ? null : type;
    }

    private static ClassNode memberType(List<ASTNode> members) {
        if (members.size() != 1) {
            return null;
        }
        ASTNode member = members.get(0);
        if (member instanceof PropertyNode property) {
            return property.getField().isDynamicTyped() ? null : property.getOriginType();
        }
        if (member instanceof FieldNode field) {
            return field.isDynamicTyped() ? null : field.getOriginType();
        }
        if (member instanceof MethodNode method) {
            return method.isDynamicReturnType() ? null : method.getReturnType();
        }
        return null;
    }

    private static ClassNode returnType(List<MethodNode> methods) {
        ClassNode type = null;
        for (MethodNode method : methods) {
            if (method.isDynamicReturnType()) {
                return null;
            }
            if (type == null) {
                type = method.getReturnType();
            } else if (!type.getName().equals(method.getReturnType().getName())) {
                return null;
            }
        }
        return type;
    }

    static boolean named(ClassNode type, Set<String> names) {
        return type != null && names.contains(type.getName());
    }

    static boolean isTrue(Expression expression) {
        return expression instanceof ConstantExpression constant && Boolean.TRUE.equals(constant.getValue());
    }

    static boolean isFalse(Expression expression) {
        return expression instanceof ConstantExpression constant && Boolean.FALSE.equals(constant.getValue());
    }

    static boolean simple(Expression expression) {
        return expression instanceof VariableExpression || expression instanceof ConstantExpression
                || expression instanceof PropertyExpression || expression instanceof FieldExpression;
    }

    /**
     * A default that is the very empty value ?: would replace ({@code name ?: ''}, {@code count ?: 0},
     * {@code items ?: []}, {@code options ?: [:]}): an empty value comes back unchanged, so only null is
     * defaulted and nothing valid is lost.
     */
    static boolean sameEmpty(ClassNode type, Expression fallback) {
        String name = type.getName();
        boolean map = name.contains("Map");
        boolean collection = map || name.contains("List") || name.contains("Collection") || name.contains("Set");
        if (fallback instanceof ConstantExpression constant) {
            Object value = constant.getValue();
            if (name.contains("String") || name.contains("CharSequence")) {
                return "".equals(value);
            }
            return !collection && value instanceof Number number && number.doubleValue() == 0;
        }
        if (fallback instanceof ListExpression list) {
            return collection && !map && list.getExpressions().isEmpty();
        }
        return fallback instanceof MapExpression entries && map && entries.getMapEntryExpressions().isEmpty();
    }
}
