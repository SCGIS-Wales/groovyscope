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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Diagnostic.Severity;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.practices.PracticeFinder;
import org.groovyscope.engine.practices.PracticeFinder.Practice;

/**
 * Runtime-profile findings (RP codes) for one governed Groovy document: constructs the runtime blocks or has not
 * proven, descriptor parameter contracts, secret flows, network calls outside their designated methods, and
 * library-reuse hints. Every message carries the profile's evidence so an agent can weigh the risk.
 */
public final class ProfileFinder {

    /** Reported as the diagnostic source. */
    public static final String SOURCE = "groovyscope-profile";
    static final Pattern ARROW_CASE = Pattern.compile("^\\s*(case\\b.*|default\\s*)->");
    static final Pattern MULTI_CATCH = Pattern.compile("\\bcatch\\s*\\(\\s*[\\w.]+\\s*\\|");
    static final java.util.Set<String> LOG_METHODS = java.util.Set.of("trace", "debug", "info", "warn", "error",
            "println", "print", "printf");

    private ProfileFinder() {
    }

    /** RP findings for a governed Groovy document. */
    public static List<Practice> find(Profile profile, String uri, ModuleNode module, String text, Path root,
            EngineConfig config) {
        List<Practice> out = new ArrayList<>();
        if (module == null || text == null) {
            return out;
        }
        Path file = org.groovyscope.engine.workspace.WorkspaceModel.toPath(uri);
        String relative = file == null ? uri : root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString();
        Optional<Descriptors.Document> descriptor = Descriptors.forFile(profile.descriptor(), root, file);
        Finder finder = new Finder(profile, uri, relative, module, new TextLines(text), config,
                descriptor.orElse(null), out);
        finder.run();
        // A node can match several spellings of the same rule (Set.of and java.util.Set.of): report it once.
        java.util.Set<Diagnostic> seen = new java.util.HashSet<>();
        out.removeIf(practice -> !seen.add(practice.diagnostic()));
        return out;
    }

    /** The severity for a construct: its own, else from its status. */
    static Severity constructSeverity(Profile.Construct construct, EngineConfig config) {
        String configured = construct.severity();
        if (configured == null || configured.isBlank()) {
            configured = switch (construct.status().toLowerCase(Locale.ROOT)) {
                case "blocked" -> "error";
                case "proven-elsewhere" -> "info";
                default -> "warning";
            };
        }
        Severity override = PracticeFinder.severity("RP001", config);
        if (override == null) {
            return null;
        }
        return switch (configured.toLowerCase(Locale.ROOT)) {
            case "off" -> null;
            case "error" -> Severity.ERROR;
            case "info" -> Severity.INFORMATION;
            default -> Severity.WARNING;
        };
    }

    private static final class Finder extends ClassCodeVisitorSupport {
        private final Profile profile;
        private final String uri;
        private final String relative;
        private final ModuleNode module;
        private final TextLines lines;
        private final EngineConfig config;
        private final Descriptors.Document descriptor;
        private final List<Practice> out;
        private final List<Pattern> secretNames = new ArrayList<>();
        private MethodNode currentMethod;

        Finder(Profile profile, String uri, String relative, ModuleNode module, TextLines lines, EngineConfig config,
                Descriptors.Document descriptor, List<Practice> out) {
            this.profile = profile;
            this.uri = uri;
            this.relative = relative;
            this.module = module;
            this.lines = lines;
            this.config = config;
            this.descriptor = descriptor;
            this.out = out;
            for (String name : profile.secrets().names()) {
                if (!name.isBlank()) {
                    // Names are fragments, not regular expressions: quoted, so no entry can fail to compile or backtrack.
                    secretNames.add(Pattern.compile(Pattern.quote(name), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                }
            }
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return module.getContext();
        }

        void run() {
            for (ImportNode importNode : allImports()) {
                String name = importNode.getClassName() != null ? importNode.getClassName() : importNode.getPackageName();
                for (Profile.Construct construct : applicable()) {
                    if (startsWithAny(name, construct.imports()) || construct.classes().contains(name)) {
                        construct(construct, importNode, "import of " + name);
                    }
                }
                for (Profile.Hint hint : profile.sdk()) {
                    if (startsWithAny(name, hint.imports()) || hint.classes().contains(name)) {
                        hint(hint, importNode);
                    }
                }
            }
            syntaxConstructs();
            for (ClassNode classNode : module.getClasses()) {
                visitClass(classNode);
            }
        }

        private List<ImportNode> allImports() {
            List<ImportNode> imports = new ArrayList<>(module.getImports());
            imports.addAll(module.getStarImports());
            imports.addAll(module.getStaticImports().values());
            imports.addAll(module.getStaticStarImports().values());
            return imports;
        }

        private List<Profile.Construct> applicable() {
            List<Profile.Construct> result = new ArrayList<>();
            for (Profile.Construct construct : profile.constructs()) {
                if (construct.files().isEmpty() || construct.files().stream()
                        .anyMatch(glob -> Profile.matches(glob, Path.of(relative)))) {
                    result.add(construct);
                }
            }
            return result;
        }

        private static boolean startsWithAny(String name, List<String> prefixes) {
            if (name == null) {
                return false;
            }
            for (String prefix : prefixes) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        // ---- reporting ----

        private void report(String code, Severity severity, Range range, String message, String fixTitle,
                List<TextEdit> fix) {
            if (severity == null || range == null) {
                return;
            }
            out.add(new Practice(new Diagnostic(uri, range, severity, SOURCE, code, message), fixTitle, fix));
        }

        private void report(String code, ASTNode node, String message, String fixTitle, List<TextEdit> fix) {
            report(code, PracticeFinder.severity(code, config), AstRanges.rangeOf(node), message, fixTitle, fix);
        }

        private String runtime() {
            String compiledBy = profile.compiledBy(relative);
            return profile.name() + (compiledBy == null ? "" : " (" + compiledBy + ")");
        }

        private void construct(Profile.Construct construct, ASTNode node, String what) {
            Range range = AstRanges.rangeOf(node);
            if (range == null) {
                range = Range.of(Math.max(0, node.getLineNumber() - 1), 0, Math.max(0, node.getLineNumber() - 1), 1);
            }
            construct(construct, range, what);
        }

        private void construct(Profile.Construct construct, Range range, String what) {
            StringBuilder message = new StringBuilder(what).append(": ").append(construct.id()).append(" is ")
                    .append(construct.status()).append(" on ").append(runtime());
            if (!construct.evidence().isBlank()) {
                message.append(" (").append(construct.evidence()).append(')');
            }
            if (!construct.replacement().isBlank()) {
                message.append(". Use instead: ").append(construct.replacement());
            }
            report("RP001", constructSeverity(construct, config), range, message.toString(), null, List.of());
        }

        private void hint(Profile.Hint hint, ASTNode node) {
            report("RP030", node, hint.id() + ": " + hint.suggest(), null, List.of());
        }

        // ---- syntax-level constructs ----

        private void syntaxConstructs() {
            for (Profile.Construct construct : applicable()) {
                for (String syntax : construct.syntax()) {
                    switch (syntax) {
                        case "switch-arrow" -> matchLines(construct, ARROW_CASE, "switch with arrow labels");
                        case "multi-catch" -> matchLines(construct, MULTI_CATCH, "multi-catch");
                        case "extra-class" -> {
                            List<ClassNode> top = module.getClasses().stream()
                                    .filter(c -> !(c instanceof InnerClassNode) && !c.isScript()).toList();
                            for (int i = 1; i < top.size(); i++) {
                                construct(construct, top.get(i), "additional class " + top.get(i).getNameWithoutPackage());
                            }
                        }
                        case "nested-class" -> {
                            for (ClassNode classNode : module.getClasses()) {
                                if (classNode instanceof InnerClassNode inner && !inner.isAnonymous()) {
                                    construct(construct, inner, "nested class " + inner.getNameWithoutPackage());
                                }
                            }
                        }
                        case "record" -> module.getClasses().stream().filter(ClassNode::isRecord)
                                .forEach(c -> construct(construct, c, "record " + c.getNameWithoutPackage()));
                        case "sealed" -> module.getClasses().stream().filter(ClassNode::isSealed)
                                .forEach(c -> construct(construct, c, "sealed type " + c.getNameWithoutPackage()));
                        default -> {
                            // "as-coercion:<Type>" is handled while visiting casts
                        }
                    }
                }
            }
        }

        private void matchLines(Profile.Construct construct, Pattern pattern, String what) {
            boolean previous = false;
            for (int i = 0; i < lines.lineCount(); i++) {
                java.util.regex.Matcher matcher = pattern.matcher(lines.line(i));
                boolean found = matcher.find();
                if (found && !previous) {
                    construct(construct, Range.of(i, matcher.start(), i, matcher.end()), what);
                }
                previous = found || previous && lines.line(i).isBlank();
            }
        }

        // ---- tree visits ----

        @Override
        protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
            MethodNode saved = currentMethod;
            currentMethod = node;
            super.visitConstructorOrMethod(node, isConstructor);
            currentMethod = saved;
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            String type = call.getType().getName();
            for (Profile.Construct construct : applicable()) {
                if (construct.constructors().contains(type) || construct.classes().contains(type)) {
                    construct(construct, call, "new " + call.getType().getNameWithoutPackage());
                }
            }
            for (Profile.Hint hint : profile.sdk()) {
                if (hint.constructors().contains(type) || hint.classes().contains(type)) {
                    hint(hint, call);
                }
            }
            checkNetwork(call, type + ".<init>", "new " + call.getType().getNameWithoutPackage());
            super.visitConstructorCallExpression(call);
        }

        @Override
        public void visitCastExpression(CastExpression cast) {
            if (cast.isCoerce()) {
                for (Profile.Construct construct : applicable()) {
                    if (construct.syntax().contains("as-coercion:" + cast.getType().getNameWithoutPackage())) {
                        construct(construct, cast, "as " + cast.getType().getNameWithoutPackage());
                    }
                }
            }
            super.visitCastExpression(cast);
        }

        @Override
        public void visitClassExpression(ClassExpression expression) {
            String type = expression.getType().getName();
            for (Profile.Construct construct : applicable()) {
                if (construct.classes().contains(type)) {
                    construct(construct, expression, type);
                }
            }
            super.visitClassExpression(expression);
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
            String owner = call.getOwnerType().getName();
            callConstructs(call, owner, call.getMethod());
            checkNetwork(call, owner + "." + call.getMethod(), call.getMethod());
            super.visitStaticMethodCallExpression(call);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String name = call.getMethodAsString();
            if (name != null) {
                ClassNode owner = call.getObjectExpression() instanceof ClassExpression type ? type.getType()
                        : PracticeFinder.typeOf(call.getObjectExpression());
                String ownerName = owner == null ? null : owner.getName();
                callConstructs(call, ownerName, name);
                checkNetwork(call, ownerName == null ? name : ownerName + "." + name, name);
                checkAccessor(call, name);
                checkRawMapCall(call, name);
                checkSecretSinks(call, name);
            }
            super.visitMethodCallExpression(call);
        }

        private void callConstructs(ASTNode call, String owner, String method) {
            for (Profile.Construct construct : applicable()) {
                for (String pattern : construct.calls()) {
                    if (callMatches(pattern, owner, method)) {
                        construct(construct, call, (owner == null ? "" : simple(owner) + ".") + method);
                    }
                }
            }
            for (Profile.Hint hint : profile.sdk()) {
                for (String pattern : hint.calls()) {
                    if (callMatches(pattern, owner, method)) {
                        hint(hint, call);
                    }
                }
            }
        }

        /** {@code method}, {@code Type.method} (simple or qualified type) or {@code Type.*}. */
        static boolean callMatches(String pattern, String owner, String method) {
            int dot = pattern.lastIndexOf('.');
            if (dot < 0) {
                return pattern.equals(method);
            }
            String type = pattern.substring(0, dot);
            String name = pattern.substring(dot + 1);
            if (!name.equals("*") && !name.equals(method)) {
                return false;
            }
            return owner != null && (owner.equals(type) || simple(owner).equals(type));
        }

        private static String simple(String name) {
            return name.substring(name.lastIndexOf('.') + 1);
        }

        private void checkNetwork(ASTNode call, String qualified, String method) {
            if (profile.network().calls().isEmpty()) {
                return;
            }
            String owner = qualified.contains(".") ? qualified.substring(0, qualified.lastIndexOf('.')) : null;
            boolean network = profile.network().calls().stream().anyMatch(p -> callMatches(p, owner, method));
            String inside = currentMethod == null ? "" : currentMethod.getName();
            if (network && !profile.network().allowedIn().contains(inside)) {
                report("RP032", call, "network call " + (owner == null ? method : simple(owner) + "." + method)
                        + " outside the designated methods " + profile.network().allowedIn(), null, List.of());
            }
        }

        // ---- descriptor contracts ----

        private Profile.Accessor accessor(String name) {
            if (profile.descriptor() == null) {
                return null;
            }
            for (Profile.Accessor accessor : profile.descriptor().accessors()) {
                if (accessor.method().equals(name)) {
                    return accessor;
                }
            }
            return null;
        }

        private static Expression argument(MethodCallExpression call, int index) {
            return call.getArguments() instanceof TupleExpression args && index >= 0 && index < args.getExpressions().size()
                    ? args.getExpression(index) : null;
        }

        private void checkAccessor(MethodCallExpression call, String name) {
            Profile.Accessor accessor = accessor(name);
            if (accessor == null || descriptor == null) {
                return;
            }
            Expression nameArgument = argument(call, accessor.nameArgument());
            if (!(nameArgument instanceof ConstantExpression constant) || !(constant.getValue() instanceof String parameter)) {
                return;
            }
            Descriptors.Parameter declared = descriptor.parameters().get(parameter);
            if (declared == null) {
                report("RP010", nameArgument, "'" + parameter + "' is not declared in " + descriptor.file().getFileName()
                        + "; declared: " + descriptor.parameters().keySet(), null, List.of());
                return;
            }
            Expression typeArgument = argument(call, accessor.typeArgument());
            if (typeArgument instanceof ClassExpression type && !declared.type().isBlank()) {
                List<String> accepted = profile.descriptor().typeMap().get(type.getType().getNameWithoutPackage());
                if (accepted != null && accepted.stream().noneMatch(t -> t.equalsIgnoreCase(declared.type()))) {
                    report("RP011", typeArgument, "'" + parameter + "' is declared as " + declared.type() + " in "
                            + descriptor.file().getFileName() + " but read as " + type.getType().getNameWithoutPackage(),
                            null, List.of());
                }
            }
        }

        @Override
        public void visitTernaryExpression(org.codehaus.groovy.ast.expr.TernaryExpression expression) {
            if (expression instanceof ElvisOperatorExpression elvis && descriptor != null
                    && elvis.getTrueExpression() instanceof MethodCallExpression call && call.getMethodAsString() != null) {
                Profile.Accessor accessor = accessor(call.getMethodAsString());
                Expression nameArgument = accessor == null ? null : argument(call, accessor.nameArgument());
                if (nameArgument instanceof ConstantExpression constant && constant.getValue() instanceof String parameter
                        && descriptor.parameters().containsKey(parameter)) {
                    Range callRange = AstRanges.rangeOf(call);
                    List<TextEdit> fix = callRange == null ? List.of()
                            : List.of(new TextEdit(AstRanges.rangeOf(elvis), lines.slice(callRange)));
                    report("RP012", elvis, "'" + parameter + "' is declared in " + descriptor.file().getFileName()
                            + ", which supplies its value before this code runs; the fallback is dead code",
                            "Remove the fallback", fix);
                }
            }
            super.visitTernaryExpression(expression);
        }

        private boolean isRawMap(Expression receiver) {
            List<String> maps = profile.descriptor() == null ? List.of() : profile.descriptor().rawMaps();
            return receiver instanceof VariableExpression variable && maps.contains(variable.getName())
                    || receiver instanceof PropertyExpression property && maps.contains(property.getPropertyAsString());
        }

        private void checkRawMapCall(MethodCallExpression call, String name) {
            if (descriptor != null && "get".equals(name) && isRawMap(call.getObjectExpression())
                    && argument(call, 0) instanceof ConstantExpression key && key.getValue() instanceof String parameter) {
                rawRead(call, parameter);
            }
        }

        @Override
        public void visitPropertyExpression(PropertyExpression expression) {
            if (descriptor != null && isRawMap(expression.getObjectExpression()) && expression.getPropertyAsString() != null
                    && !expression.isSpreadSafe()) {
                rawRead(expression, expression.getPropertyAsString());
            }
            super.visitPropertyExpression(expression);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            if (descriptor != null && expression.getOperation().getType() == Types.LEFT_SQUARE_BRACKET
                    && isRawMap(expression.getLeftExpression())
                    && expression.getRightExpression() instanceof ConstantExpression key && key.getValue() instanceof String parameter) {
                rawRead(expression, parameter);
            }
            if (expression.getOperation().getType() == Types.ASSIGN
                    && expression.getLeftExpression() instanceof BinaryExpression index
                    && index.getOperation().getType() == Types.LEFT_SQUARE_BRACKET) {
                String map = mapName(index.getLeftExpression());
                if (map != null && profile.secrets().maps().contains(map) && mentionsSecret(expression.getRightExpression())) {
                    secret(expression, "into " + map);
                }
            }
            super.visitBinaryExpression(expression);
        }

        private void rawRead(ASTNode node, String parameter) {
            if (!descriptor.parameters().containsKey(parameter)) {
                return;
            }
            Descriptors.Parameter declared = descriptor.parameters().get(parameter);
            List<TextEdit> fix = List.of();
            Profile.Accessor accessor = profile.descriptor().accessors().stream().filter(a -> a.typeArgument() > a.nameArgument())
                    .findFirst().orElse(null);
            String javaType = accessor == null ? null : groovyType(declared.type());
            if (accessor != null && javaType != null && accessor.nameArgument() == 0 && accessor.typeArgument() == 1) {
                fix = List.of(new TextEdit(AstRanges.rangeOf(node), accessor.method() + "('" + parameter + "', " + javaType + ".class)"));
            }
            report("RP013", node, "'" + parameter + "' is read from the raw map; it is declared in "
                    + descriptor.file().getFileName() + (accessor == null ? "" : ", so use " + accessor.method() + "()"),
                    "Use the typed accessor", fix);
        }

        private String groovyType(String descriptorType) {
            for (Map.Entry<String, List<String>> entry : profile.descriptor().typeMap().entrySet()) {
                if (entry.getValue().stream().anyMatch(t -> t.equalsIgnoreCase(descriptorType))) {
                    return entry.getKey();
                }
            }
            return null;
        }

        // ---- secrets ----

        private boolean isSecretName(String name) {
            for (Pattern pattern : secretNames) {
                if (pattern.matcher(name).find()) {
                    return true;
                }
            }
            return false;
        }

        private boolean mentionsSecret(Expression expression) {
            if (secretNames.isEmpty() || expression == null) {
                return false;
            }
            boolean[] found = {false};
            expression.visit(new org.codehaus.groovy.ast.CodeVisitorSupport() {
                @Override
                public void visitVariableExpression(VariableExpression variable) {
                    if (isSecretName(variable.getName())) {
                        found[0] = true;
                    }
                }

                @Override
                public void visitPropertyExpression(PropertyExpression property) {
                    if (property.getPropertyAsString() != null && isSecretName(property.getPropertyAsString())) {
                        found[0] = true;
                    }
                    super.visitPropertyExpression(property);
                }
            });
            return found[0];
        }

        private void secret(ASTNode node, String sink) {
            report("RP022", node, "a secret-named value flows " + sink + "; secrets must not reach logs, exception "
                    + "text, parameter maps or outputs", null, List.of());
        }

        private static String mapName(Expression receiver) {
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

        private void checkSecretSinks(MethodCallExpression call, String name) {
            if (secretNames.isEmpty()) {
                return;
            }
            Expression arguments = call.getArguments();
            if (profile.secrets().logs() && LOG_METHODS.contains(name) && mentionsSecret(arguments)) {
                secret(call, "into a log call");
            } else if (profile.secrets().calls().contains(name) && mentionsSecret(arguments)) {
                secret(call, "into " + name + "()");
            } else if (java.util.Set.of("put", "putAt", "leftShift").contains(name)) {
                String map = mapName(call.getObjectExpression());
                if (map != null && profile.secrets().maps().contains(map) && mentionsSecret(arguments)) {
                    secret(call, "into " + map);
                }
            }
        }

        @Override
        public void visitThrowStatement(ThrowStatement statement) {
            if (profile.secrets().exceptions() && statement.getExpression() instanceof ConstructorCallExpression created
                    && mentionsSecret(created.getArguments())) {
                secret(statement, "into an exception message");
            }
            super.visitThrowStatement(statement);
        }
    }
}
