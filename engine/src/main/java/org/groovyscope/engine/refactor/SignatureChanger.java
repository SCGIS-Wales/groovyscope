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
package org.groovyscope.engine.refactor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression;
import org.codehaus.groovy.ast.expr.NotExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.RangeExpression;
import org.codehaus.groovy.ast.expr.SpreadExpression;
import org.codehaus.groovy.ast.expr.SpreadMapExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.UnaryMinusExpression;
import org.codehaus.groovy.ast.expr.UnaryPlusExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.concurrent.Cancellation;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.index.Declarations;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.search.SymbolEditor;
import org.groovyscope.engine.services.Snapshot;

/**
 * Changes a method's parameter list (order, added and removed parameters, their types) and its return type, with
 * every call site whose target is known: arguments reordered, removed parameters' arguments dropped, added
 * parameters' defaults inserted. Refuses rather than guesses: a call matched by name only, named or spread
 * arguments, a dropped argument with side effects, an added parameter without a default, an override.
 */
public final class SignatureChanger {

    private static final String WHAT = "change signature";

    private SignatureChanger() {
    }

    /**
     * One parameter of the new list.
     *
     * @param name its name; an old parameter of that name keeps its arguments
     * @param type its type as written, or {@code null} to keep an old parameter's ({@code def} for a new one)
     * @param defaultValue for an added parameter, the expression its call sites pass
     */
    public record NewParameter(String name, String type, String defaultValue) {
    }

    /**
     * A change.
     *
     * @param symbol the method, as {@code groovyscope/edit} names it
     * @param line 1-based line that picks one of several overloads, or {@code null}
     * @param parameters the whole new parameter list, in its new order
     * @param returnType the new return type, or {@code null} to keep it
     */
    public record Request(String symbol, Integer line, List<NewParameter> parameters, String returnType) {
        public Request {
            parameters = List.copyOf(parameters);
        }
    }

    /**
     * What the change does, or why it is refused.
     *
     * @param refused the reason nothing is changed, or {@code null}
     * @param changes the edits
     * @param callSites the call sites rewritten
     * @param declaration the new signature, on one line
     */
    public record Result(String refused, Changes changes, int callSites, String declaration) {

        static Result refusal(String reason) {
            return new Result(reason, Changes.none(), 0, null);
        }
    }

    /** A rewritten call: the span between its parentheses and the new argument list. */
    private record Site(String uri, int open, int close, List<Expression> arguments) {
    }

    /**
     * Computes the change of the method {@code request} names in the document {@code uri} of {@code snapshot}.
     *
     * @throws IllegalArgumentException when the document is not compiled, the symbol is not found or not a method,
     *                                  or a parameter is malformed
     */
    public static Result change(Snapshot snapshot, String uri, Request request) {
        ModuleNode module = snapshot.result().modules().get(uri);
        String source = snapshot.result().sources().get(uri);
        if (module == null || source == null) {
            throw new IllegalArgumentException(uri + " is not a compiled Groovy document of the workspace");
        }
        Declarations.Located located = SymbolEditor.resolve(Declarations.located(module),
                TypeText.packageOf(module), request.symbol(), request.line());
        if (!(located.node() instanceof MethodNode method) || method instanceof ConstructorNode) {
            throw new IllegalArgumentException("change signature needs a method; " + located.declaration().name()
                    + " is a " + located.declaration().kind().label());
        }
        Set<String> names = new HashSet<>();
        for (NewParameter parameter : request.parameters()) {
            if (!SourceText.identifier(parameter.name())) {
                throw new IllegalArgumentException("parameter name " + parameter.name() + " is not an identifier");
            }
            if (!names.add(parameter.name())) {
                throw new IllegalArgumentException("parameter " + parameter.name() + " is named twice");
            }
        }
        return new SignatureChanger.Work(snapshot, uri, source, method, request).run();
    }

    /** One change being computed. */
    private static final class Work {

        private final Snapshot snapshot;
        private final String uri;
        private final String source;
        private final TextLines lines;
        private final MethodNode method;
        private final Request request;
        private final Parameter[] old;
        private final Map<String, Integer> oldIndex = new HashMap<>();
        private final Map<String, TextLines> linesByUri = new HashMap<>();

        Work(Snapshot snapshot, String uri, String source, MethodNode method, Request request) {
            this.snapshot = snapshot;
            this.uri = uri;
            this.source = source;
            this.lines = new TextLines(source);
            this.method = method;
            this.request = request;
            this.old = method.getParameters();
            for (int i = 0; i < old.length; i++) {
                oldIndex.put(old[i].getName(), i);
            }
        }

        Result run() {
            String hierarchy = hierarchy(snapshot, method);
            if (hierarchy != null) {
                return Result.refusal(hierarchy);
            }
            List<NewParameter> wanted = request.parameters();
            Usages usages = Usages.of(snapshot, method);
            String refusal = usages.dynamicRefusal(WHAT);
            if (refusal == null) {
                refusal = usages.otherRefusal(WHAT);
            }
            if (refusal != null) {
                return Result.refusal(refusal);
            }
            if (!usages.calls().isEmpty() && old.length > 0 && old[old.length - 1].getType().isArray()) {
                String last = old[old.length - 1].getName();
                if (wanted.isEmpty() || !wanted.get(wanted.size() - 1).name().equals(last)) {
                    return Result.refusal("the last parameter " + last + " is an array, which Groovy also fills from "
                            + "a variable argument list; keep it last");
                }
            }
            if (!usages.calls().isEmpty()) {
                for (NewParameter parameter : wanted) {
                    if (!oldIndex.containsKey(parameter.name()) && blank(parameter.defaultValue())) {
                        return Result.refusal("the added parameter " + parameter.name() + " needs a default: "
                                + usages.calls().size() + " call site(s) must pass it");
                    }
                }
            }
            Set<String> kept = new HashSet<>();
            for (NewParameter parameter : wanted) {
                kept.add(parameter.name());
            }
            List<Site> sites = new ArrayList<>();
            for (Usages.Call call : usages.calls()) {
                Cancellation.checkpoint();
                Object site = site(call, kept);
                if (site instanceof String reason) {
                    return Result.refusal(reason);
                }
                sites.add((Site) site);
            }
            Changes.Builder builder = new Changes.Builder();
            Map<String, List<Changes.Span>> committed = new LinkedHashMap<>();
            sites.sort(Comparator.comparingInt((Site site) -> site.close() - site.open()));
            for (Site site : sites) {
                String text = snapshot.result().sources().get(site.uri());
                List<Changes.Span> spans = committed.computeIfAbsent(site.uri(), key -> new ArrayList<>());
                List<String> arguments = new ArrayList<>();
                for (NewParameter parameter : wanted) {
                    Integer index = oldIndex.get(parameter.name());
                    if (index == null) {
                        arguments.add(parameter.defaultValue().strip());
                    } else {
                        Expression argument = site.arguments().get(index);
                        arguments.add(Changes.slice(text, SourceText.start(textLines(site.uri()), argument),
                                SourceText.end(textLines(site.uri()), argument), spans));
                    }
                }
                spans.removeIf(span -> span.start() >= site.open() + 1 && span.end() <= site.close());
                spans.add(new Changes.Span(site.open() + 1, site.close(), String.join(", ", arguments)));
            }
            List<Changes.Span> declaration = declarationSpans();
            if (declaration == null) {
                return Result.refusal("the parameter list of " + method.getName() + " was not found in the source");
            }
            committed.computeIfAbsent(uri, key -> new ArrayList<>()).addAll(declaration);
            for (Map.Entry<String, List<Changes.Span>> entry : committed.entrySet()) {
                String text = snapshot.result().sources().get(entry.getKey());
                for (Changes.Span span : entry.getValue()) {
                    builder.replace(entry.getKey(), text, span.start(), span.end(), span.text());
                }
            }
            Changes changes;
            try {
                changes = builder.build();
            } catch (IllegalStateException e) {
                return Result.refusal(e.getMessage() + "; " + WHAT + " refused");
            }
            return new Result(null, changes, sites.size(), SourceText.header(source, lines, method,
                    committed.get(uri)));
        }

        private TextLines textLines(String document) {
            return linesByUri.computeIfAbsent(document, key -> new TextLines(snapshot.result().sources().get(key)));
        }

        /** The site of one call, or the reason it cannot be rewritten. */
        private Object site(Usages.Call call, Set<String> kept) {
            String text = snapshot.result().sources().get(call.uri());
            if (text == null) {
                return "the source of " + SourceText.fileName(call.uri()) + " is not in the compile; " + WHAT
                        + " refused";
            }
            TextLines callLines = textLines(call.uri());
            Expression arguments = call.call() instanceof MethodCallExpression invocation ? invocation.getArguments()
                    : ((StaticMethodCallExpression) call.call()).getArguments();
            if (!(arguments instanceof ArgumentListExpression list) || list.getExpressions().stream()
                    .anyMatch(argument -> named(argument, text, callLines))) {
                return "the call at " + call.where() + " uses named arguments; " + WHAT + " refused";
            }
            List<Expression> values = list.getExpressions();
            for (Expression argument : values) {
                if (argument instanceof SpreadExpression || argument instanceof SpreadMapExpression) {
                    return "the call at " + call.where() + " spreads its arguments; " + WHAT + " refused";
                }
            }
            int nameEnd;
            if (call.call() instanceof StaticMethodCallExpression invocation) {
                int start = SourceText.callName(text, callLines.offsetOf(call.entry().range().start()),
                        callLines.offsetOf(call.entry().range().end()), invocation.getMethod());
                if (start < 0) {
                    return "the name of the call at " + call.where() + " was not found in the source; " + WHAT
                            + " refused";
                }
                nameEnd = start + invocation.getMethod().length();
            } else {
                nameEnd = callLines.offsetOf(call.entry().range().end());
            }
            int open = SourceText.skipBlank(text, nameEnd);
            if (open >= text.length() || text.charAt(open) != '(') {
                return "the call at " + call.where() + " has no parentheses; add them first";
            }
            int close = SourceText.matching(text, open);
            if (close < 0) {
                return "the parentheses of the call at " + call.where() + " do not close; " + WHAT + " refused";
            }
            for (Expression argument : values) {
                int start = SourceText.start(callLines, argument);
                int end = SourceText.end(callLines, argument);
                if (start < 0 || end < start) {
                    return "an argument of the call at " + call.where() + " has no position; " + WHAT + " refused";
                }
                if (start > close) {
                    return "the call at " + call.where() + " passes a closure after its parentheses; move it inside "
                            + "first";
                }
            }
            if (values.size() != old.length) {
                return "the call at " + call.where() + " passes " + values.size() + " argument(s) to "
                        + old.length + " parameter(s) (default values or a variable argument list); " + WHAT
                        + " refused";
            }
            for (int i = 0; i < old.length; i++) {
                if (!kept.contains(old[i].getName()) && !harmless(values.get(i))) {
                    return "the call at " + call.where() + " passes `" + SourceText.of(callLines, values.get(i))
                            + "` to the removed parameter " + old[i].getName() + ", and dropping it may drop a side "
                            + "effect (only a literal, a variable or a property read is dropped)";
                }
            }
            return new Site(call.uri(), open, close, values);
        }

        /** The spans that rewrite the declaration's parameter list and return type, or {@code null}. */
        private List<Changes.Span> declarationSpans() {
            Range whole = AstRanges.rangeOf(method);
            Range name = AstRanges.nameRange(method, whole, lines);
            if (whole == null || name == null || name.equals(whole)) {
                return null;
            }
            int nameStart = lines.offsetOf(name.start());
            int open = SourceText.skipBlank(source, lines.offsetOf(name.end()));
            if (open >= source.length() || source.charAt(open) != '(') {
                return null;
            }
            int close = SourceText.matching(source, open);
            if (close < 0) {
                return null;
            }
            List<String> parameters = new ArrayList<>();
            for (NewParameter parameter : request.parameters()) {
                Integer index = oldIndex.get(parameter.name());
                parameters.add(index == null ? (blank(parameter.type()) ? "def" : parameter.type().strip()) + " "
                        + parameter.name() : keptText(old[index], parameter));
            }
            List<Changes.Span> spans = new ArrayList<>();
            spans.add(new Changes.Span(open + 1, close, String.join(", ", parameters)));
            if (!blank(request.returnType())) {
                String type = request.returnType().strip();
                int[] current = SourceText.typeBefore(source, nameStart);
                if (current == null) {
                    spans.add(new Changes.Span(nameStart, nameStart, type + " "));
                } else if (!squash(source.substring(current[0], current[1])).equals(squash(type))) {
                    spans.add(new Changes.Span(current[0], current[1], type));
                }
            }
            return spans;
        }

        /** An old parameter as the new list writes it: as it was, unless its type changes. */
        private String keptText(Parameter parameter, NewParameter wanted) {
            String text = SourceText.of(lines, parameter);
            String written = text == null ? null : writtenType(parameter, text);
            if (text != null && (blank(wanted.type()) || written != null && sameType(written, wanted.type()))) {
                return text.strip();
            }
            String type = blank(wanted.type()) ? "def" : wanted.type().strip();
            String initial = parameter.hasInitialExpression()
                    ? SourceText.of(lines, parameter.getInitialExpression()) : null;
            return type + " " + parameter.getName() + (initial == null ? "" : " = " + initial);
        }

        /** The type a parameter's text writes before its name, without annotations and {@code final}. */
        private String writtenType(Parameter parameter, String text) {
            String head = text;
            int equals = head.indexOf('=');
            if (parameter.hasInitialExpression() && equals >= 0) {
                head = head.substring(0, equals);
            }
            head = head.strip();
            if (!head.endsWith(parameter.getName())) {
                return null;
            }
            head = head.substring(0, head.length() - parameter.getName().length()).strip();
            head = SourceText.LEADING_ANNOTATIONS.matcher(head).replaceFirst("").strip();
            if (head.startsWith("final ")) {
                head = head.substring("final ".length()).strip();
            }
            return head;
        }

        private static boolean sameType(String written, String wanted) {
            String a = squash(written);
            String b = squash(wanted);
            if (a.isEmpty() || a.equals("def")) {
                return b.isEmpty() || b.equals("def") || b.equals("Object");
            }
            return a.equals(b);
        }
    }

    /**
     * The reason {@code method} cannot change alone: it overrides a superclass or interface method, or a workspace
     * class overrides it; {@code null} when neither holds (static methods never do).
     */
    static String hierarchy(Snapshot snapshot, MethodNode method) {
        if (method.isStatic()) {
            return null;
        }
        Parameter[] old = method.getParameters();
        ClassNode owner = method.getDeclaringClass();
        List<ClassNode> above = new ArrayList<>();
        for (ClassNode type = owner.getSuperClass(); type != null; type = type.getSuperClass()) {
            above.add(type);
        }
        above.addAll(owner.getAllInterfaces());
        for (ClassNode type : above) {
            if (type.equals(owner)) {
                continue;
            }
            for (MethodNode candidate : type.getMethods(method.getName())) {
                if (!candidate.isStatic() && sameParameters(candidate.getParameters(), old)) {
                    return method.getName() + " overrides " + TypeText.simpleName(type) + "." + method.getName()
                            + "; change the two together";
                }
            }
        }
        for (ClassNode type : snapshot.index().classes()) {
            if (type.equals(owner) || !(type.isDerivedFrom(owner) || type.implementsInterface(owner))) {
                continue;
            }
            for (MethodNode candidate : type.getDeclaredMethods(method.getName())) {
                if (sameParameters(candidate.getParameters(), old)) {
                    return TypeText.simpleName(type) + "." + method.getName() + " overrides it (line "
                            + candidate.getLineNumber() + " of " + SourceText.fileName(type.getModule() == null
                            ? null : type.getModule().getDescription()) + "); change the two together";
                }
            }
        }
        return null;
    }

    /** Whether two parameter lists have the same types, erased. */
    static boolean sameParameters(Parameter[] a, Parameter[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!a[i].getType().getName().equals(b[i].getType().getName())) {
                return false;
            }
        }
        return true;
    }

    /** Whether an argument is named ({@code a: 1}): a map written without brackets. */
    private static boolean named(Expression argument, String text, TextLines lines) {
        if (argument instanceof NamedArgumentListExpression) {
            return true;
        }
        if (!(argument instanceof MapExpression)) {
            return false;
        }
        int start = SourceText.start(lines, argument);
        return start < 0 || start >= text.length() || text.charAt(start) != '[';
    }

    /** Whether evaluating {@code expression} has no side effect: a literal, a variable, a property read. */
    static boolean harmless(Expression expression) {
        if (expression instanceof ConstantExpression || expression instanceof VariableExpression
                || expression instanceof ClassExpression || expression instanceof ClosureExpression) {
            return true;
        }
        if (expression instanceof PropertyExpression property) {
            return !property.isSpreadSafe() && harmless(property.getObjectExpression());
        }
        if (expression instanceof GStringExpression gstring) {
            return gstring.getValues().stream().allMatch(SignatureChanger::harmless);
        }
        if (expression instanceof ListExpression list) {
            return list.getExpressions().stream().allMatch(SignatureChanger::harmless);
        }
        if (expression instanceof MapExpression map) {
            for (MapEntryExpression entry : map.getMapEntryExpressions()) {
                if (!harmless(entry.getKeyExpression()) || !harmless(entry.getValueExpression())) {
                    return false;
                }
            }
            return true;
        }
        if (expression instanceof RangeExpression range) {
            return harmless(range.getFrom()) && harmless(range.getTo());
        }
        if (expression instanceof UnaryMinusExpression minus) {
            return harmless(minus.getExpression());
        }
        if (expression instanceof UnaryPlusExpression plus) {
            return harmless(plus.getExpression());
        }
        if (expression instanceof NotExpression not) {
            return harmless(not.getExpression());
        }
        if (expression instanceof CastExpression cast) {
            return harmless(cast.getExpression());
        }
        return expression instanceof TupleExpression tuple && !(expression instanceof ArgumentListExpression)
                && tuple.getExpressions().stream().allMatch(SignatureChanger::harmless);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static String squash(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }
}
