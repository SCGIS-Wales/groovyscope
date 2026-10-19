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
package org.groovyscope.engine.explain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.groovyscope.engine.callgraph.CallGraph;
import org.groovyscope.engine.model.CallSite;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.practices.TestDoubleFinder;
import org.groovyscope.engine.workspace.TestSources;

/**
 * What a test of one method needs, in one answer: each collaborator it calls with its visibility and how a test can
 * replace it, the line where the tests already stub a method of that name, and the test classes that exercise the
 * method's class with their helper methods. Built so an agent writing tests reads no file to find the seams.
 */
public final class TestSeams {

    static final int MAX_COLLABORATORS = 30;
    static final int MAX_TEST_CLASSES = 3;
    static final int MAX_HELPERS = 25;
    static final int EXAMPLE_CHARS = 160;
    static final Set<String> LIFECYCLE = Set.of("setup", "cleanup", "setupSpec", "cleanupSpec", "setUp", "tearDown");
    static final Set<String> LIFECYCLE_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory",
            "BeforeEach", "AfterEach", "BeforeAll", "AfterAll", "Before", "After", "BeforeClass", "AfterClass");

    /** One method the explained method calls, and how a test replaces it. */
    public record Collaborator(String target, String visibility, boolean isStatic, boolean compileStatic, String stub,
            String stubbedAt, String example) {
    }

    /** A test class that exercises the method's class, and the helpers it already has. */
    public record TestClass(String name, String uri, List<String> helpers) {
    }

    /** The answer: collaborators, test classes, and a note when the method itself is private. */
    public record Seams(List<Collaborator> collaborators, List<TestClass> tests, String direct) {
    }

    private TestSeams() {
    }

    /**
     * The seams of {@code method}. {@code declarations} maps a call graph symbol back to its declaration,
     * {@code modules} is every compiled document by URI and {@code texts} gives a document's text.
     */
    public static Seams of(MethodNode method, CallGraph graph, Function<Symbol, Optional<ASTNode>> declarations,
            Map<String, ModuleNode> modules, Function<String, String> texts) {
        ClassNode owner = method.getDeclaringClass();
        Map<String, Example> examples = examples(modules, texts, owner);
        Map<String, Collaborator> collaborators = new LinkedHashMap<>();
        for (CallSite site : graph.outgoing(method)) {
            Optional<ASTNode> declaration = declarations.apply(site.callee());
            if (declaration.isEmpty() || !(declaration.get() instanceof MethodNode callee)
                    || callee.getDeclaringClass() == null || collaborators.size() >= MAX_COLLABORATORS) {
                continue;
            }
            String target = callee.getDeclaringClass().getName() + "." + callee.getName();
            Example example = examples.get(callee.getName());
            collaborators.putIfAbsent(target, new Collaborator(target, CodeFacts.visibility(callee.getModifiers()),
                    callee.isStatic(), compileStatic(callee), advice(callee),
                    example == null ? null : example.at(), example == null ? null : example.text()));
        }
        String direct = method.isPrivate()
                ? "private: a dynamic Groovy test calls it directly (" + method.getName() + "(...) on an instance), "
                        + "no reflection needed; a @CompileStatic test cannot"
                : null;
        return new Seams(List.copyOf(collaborators.values()), testClasses(method, owner, graph, modules), direct);
    }

    static boolean compileStatic(MethodNode method) {
        return annotated(method.getAnnotations(), "CompileStatic")
                || annotated(method.getDeclaringClass().getAnnotations(), "CompileStatic");
    }

    static boolean annotated(List<AnnotationNode> annotations, String simpleName) {
        return annotations.stream().anyMatch(a -> a.getClassNode().getNameWithoutPackage().equals(simpleName));
    }

    /** How a test replaces a collaborator, from its visibility. */
    static String advice(MethodNode callee) {
        if (callee.isPrivate()) {
            return "private: no Mockito or Spock double can stub it; drive it through its inputs";
        }
        if (callee.isStatic()) {
            return "static: Mockito.mockStatic, or wrap it in an instance seam";
        }
        String metaClass = compileStatic(callee) ? "; a metaClass override never reaches it from @CompileStatic code"
                : "";
        return "spy the instance and doReturn(..).when(spy)." + callee.getName() + "(..), or Spock Spy() and >>"
                + metaClass;
    }

    record Example(String at, String text) {
    }

    /** The first stub of each method name in the test sources, preferring files that name {@code owner}. */
    static Map<String, Example> examples(Map<String, ModuleNode> modules, Function<String, String> texts,
            ClassNode owner) {
        Map<String, Example> preferred = new LinkedHashMap<>();
        Map<String, Example> other = new LinkedHashMap<>();
        for (String uri : new TreeSet<>(modules.keySet())) {
            if (!TestSources.isTestPath(uri) || uri.endsWith(".java")) {
                continue;
            }
            String text = texts.apply(uri);
            String[] lines = text == null ? new String[0] : text.split("\n", -1);
            boolean namesOwner = text != null && text.contains(owner.getNameWithoutPackage());
            for (TestDoubleFinder.StubSite site : TestDoubleFinder.stubSites(modules.get(uri))) {
                int line = site.node().getLineNumber();
                if (line < 1 || line > lines.length) {
                    continue;
                }
                String shown = lines[line - 1].strip();
                Example example = new Example(fileName(uri) + ":" + line,
                        shown.length() > EXAMPLE_CHARS ? shown.substring(0, EXAMPLE_CHARS) + "…" : shown);
                (namesOwner ? preferred : other).putIfAbsent(site.method(), example);
            }
        }
        other.forEach(preferred::putIfAbsent);
        return preferred;
    }

    static String fileName(String uri) {
        return uri.substring(uri.lastIndexOf('/') + 1);
    }

    /** Test classes that call into the method's class, or are named after it, with their helpers. */
    static List<TestClass> testClasses(MethodNode method, ClassNode owner, CallGraph graph,
            Map<String, ModuleNode> modules) {
        Set<String> names = new LinkedHashSet<>();
        for (CallSite site : graph.incoming(method)) {
            if (site.caller() != null && site.caller().location() != null
                    && TestSources.isTestPath(site.caller().location().uri()) && site.caller().container() != null) {
                names.add(site.caller().container());
            }
        }
        List<TestClass> named = new ArrayList<>();
        List<TestClass> calling = new ArrayList<>();
        String prefix = owner.getNameWithoutPackage();
        for (String uri : new TreeSet<>(modules.keySet())) {
            if (!TestSources.isTestPath(uri)) {
                continue;
            }
            for (ClassNode classNode : modules.get(uri).getClasses()) {
                String simpleName = classNode.getNameWithoutPackage();
                if (classNode.getLineNumber() < 1) {
                    continue;
                }
                if (simpleName.startsWith(prefix) && TestSources.isTestName(simpleName)) {
                    named.add(new TestClass(classNode.getName(), uri, helpers(classNode)));
                } else if (names.contains(classNode.getName()) || names.contains(simpleName)) {
                    calling.add(new TestClass(classNode.getName(), uri, helpers(classNode)));
                }
            }
        }
        named.addAll(calling);
        return List.copyOf(named.subList(0, Math.min(MAX_TEST_CLASSES, named.size())));
    }

    /** A test class's own methods that are neither tests, Spock features nor lifecycle methods. */
    static List<String> helpers(ClassNode testClass) {
        List<String> helpers = new ArrayList<>();
        for (MethodNode method : testClass.getMethods()) {
            if (method.getLineNumber() < 1 || method.isSynthetic() || LIFECYCLE.contains(method.getName())
                    || method.getName().contains(" ") || method.getAnnotations().stream().anyMatch(
                            a -> LIFECYCLE_ANNOTATIONS.contains(a.getClassNode().getNameWithoutPackage()))) {
                continue;
            }
            List<String> parameters = new ArrayList<>();
            for (Parameter parameter : method.getParameters()) {
                parameters.add(parameter.getOriginType().getNameWithoutPackage() + " " + parameter.getName());
            }
            helpers.add(method.getName() + "(" + String.join(", ", parameters) + ") line " + method.getLineNumber());
            if (helpers.size() >= MAX_HELPERS) {
                break;
            }
        }
        return helpers;
    }
}
