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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CompileUnit;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.stmt.Statement;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.log.EngineLog;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.workspace.TestSources;

/**
 * GS046, a test class out of proportion to the class it tests: more test lines than {@code tests.maxTestToCodeRatio}
 * times the lines of the class under test, or more test methods than {@code tests.maxMethodsPerSubject}. The class
 * under test is found by name ({@code FooSpec}, {@code FooTest}, {@code FooTests}, {@code FooIT} test {@code Foo})
 * among the main sources: first in the trees compiled with the test (a whole workspace compile holds every source
 * set), then on disk in the {@code main} source set beside the test's own (a single file or candidate compile holds
 * the test alone). Lines are whole file lines on both sides, as {@code scripts/edit_gate_metrics.py} measures them;
 * when the class under test is not found, nothing is reported.
 */
final class TestSize {

    /** A file in a source set: the folder holding {@code src/}, the source set, the language, the package path. */
    private static final Pattern SOURCE_SET = Pattern.compile("^(.*/src/)([A-Za-z0-9_.-]+)/(groovy|java)/(.*/)?[^/]+$");
    private static final List<String> LANGUAGES = List.of("groovy", "java");

    private TestSize() {
    }

    /** Reports GS046 for one test class with {@code tests} test methods, when it is out of proportion. */
    static void check(PracticeContext context, ClassNode testClass, int tests) {
        String subject = subjectOf(testClass.getNameWithoutPackage());
        if (subject == null || testClass.getOuterClass() != null) {
            return;
        }
        Integer main = mainLines(context, testClass, subject);
        if (main == null || main < 1) {
            return;
        }
        EngineConfig.Tests settings = context.config().practices().tests();
        int lines = lineCount(context.lines().text());
        double ratio = (double) lines / main;
        if (ratio <= settings.maxTestToCodeRatio() && tests <= settings.maxMethodsPerSubject()) {
            return;
        }
        Range whole = AstRanges.rangeOf(testClass);
        Range at = AstRanges.nameRange(testClass, whole, context.lines());
        if (at != null) {
            context.report("GS046", at, String.format(Locale.ROOT, "%s has %d lines for the %d lines of %s (%.1f times, "
                    + "at most %.1f) and %d test methods (at most %d): one test per behaviour, repeated tests merged "
                    + "into data tables, trivial ones deleted", testClass.getNameWithoutPackage(), lines, main, subject,
                    ratio, settings.maxTestToCodeRatio(), tests, settings.maxMethodsPerSubject()), null, List.of());
        }
    }

    /** The class a test class tests, by name ({@code FooSpec} tests {@code Foo}), or {@code null}. */
    static String subjectOf(String testClass) {
        List<String> suffixes = new ArrayList<>(TestSources.TEST_SUFFIXES);
        suffixes.sort(Comparator.comparingInt(String::length).reversed());
        for (String suffix : suffixes) {
            if (testClass.endsWith(suffix) && testClass.length() > suffix.length()) {
                return testClass.substring(0, testClass.length() - suffix.length());
            }
        }
        return null;
    }

    /** Lines of a text as an editor numbers them: a final line break does not start another line. */
    static int lineCount(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && i + 1 < text.length()) {
                count++;
            }
        }
        return count;
    }

    /** The lines of the file holding the class under test, or {@code null} when it is not found. */
    private static Integer mainLines(PracticeContext context, ClassNode testClass, String subject) {
        Integer compiled = compiledLines(context.module(), testClass.getPackageName(), subject);
        return compiled != null ? compiled : diskLines(context, subject);
    }

    /** From the trees compiled with the test: a main source class of that name, its own package first. */
    private static Integer compiledLines(ModuleNode module, String testPackage, String subject) {
        CompileUnit unit = module.getUnit();
        if (unit == null) {
            return null;
        }
        Integer elsewhere = null;
        int found = 0;
        for (ModuleNode other : unit.getModules()) {
            if (other == module || other.getDescription() == null || TestSources.isTestPath(other.getDescription())) {
                continue;
            }
            for (ClassNode candidate : other.getClasses()) {
                if (!subject.equals(candidate.getNameWithoutPackage()) || candidate.getLineNumber() < 1) {
                    continue;
                }
                int lines = lastLine(other);
                if (java.util.Objects.equals(candidate.getPackageName(), testPackage)) {
                    return lines;
                }
                elsewhere = lines;
                found++;
            }
        }
        return found == 1 ? elsewhere : null;
    }

    /** The last line a module's classes and statements reach: its line count, trailing blank lines aside. */
    private static int lastLine(ModuleNode module) {
        int last = 0;
        for (ClassNode classNode : module.getClasses()) {
            last = Math.max(last, classNode.getLastLineNumber());
        }
        for (Statement statement : module.getStatementBlock().getStatements()) {
            last = Math.max(last, statement.getLastLineNumber());
        }
        return last;
    }

    /** From disk: {@code src/main/<language>/<package path>/<subject>.groovy} or {@code .java} beside the test. */
    private static Integer diskLines(PracticeContext context, String subject) {
        Matcher matcher = SOURCE_SET.matcher(context.uri().replace('\\', '/'));
        if (!matcher.matches() || !context.uri().startsWith("file:")) {
            return null;
        }
        String folder = matcher.group(4) == null ? "" : matcher.group(4);
        for (String language : LANGUAGES) {
            for (String extension : LANGUAGES) {
                String candidate = matcher.group(1) + "main/" + language + "/" + folder + subject + "." + extension;
                Integer lines = fileLines(candidate, context.config().maxFileBytes());
                if (lines != null) {
                    return lines;
                }
            }
        }
        return null;
    }

    private static Integer fileLines(String uri, long maxBytes) {
        try {
            Path path = Path.of(URI.create(uri));
            if (!Files.isRegularFile(path) || Files.size(path) > maxBytes) {
                return null;
            }
            return lineCount(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            EngineLog.debug("class under test not read from %s: %s", uri, e);
            return null;
        }
    }
}
