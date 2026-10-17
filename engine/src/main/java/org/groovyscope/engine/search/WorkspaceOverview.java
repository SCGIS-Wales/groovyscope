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
package org.groovyscope.engine.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * A size map of the workspace for orientation: files and lines per package, the largest files and classes, how many
 * files are tests, and main classes no test class is named after.
 */
public final class WorkspaceOverview {

    static final int TOP = 20;
    static final String DEFAULT_PACKAGE = "(default)";
    static final List<String> TEST_SUFFIXES = List.of("Test", "Spec", "Tests");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");

    /** Totals of one package. */
    public record PackageStats(String name, int files, int classes, int lines) {
    }

    /** One document's size; {@code members} counts methods, constructors, fields and properties. */
    public record FileStats(String uri, int lines, int classes, int members) {
    }

    /** One class's size; {@code name} is qualified. */
    public record ClassStats(String name, String uri, int lines, int members) {
    }

    /** The overview. */
    public record Overview(int files, int lines, List<PackageStats> packages, List<FileStats> largestFiles,
            List<ClassStats> largestClasses, int testFiles, List<String> untested) {
    }

    private WorkspaceOverview() {
    }

    /**
     * Builds the overview from the Groovy documents ({@code sources}, keyed by URI) and whatever compiled of them
     * ({@code modules}); a document that did not compile still counts its files and lines.
     */
    public static Overview of(Path root, Map<String, String> sources, Map<String, ModuleNode> modules) {
        Map<String, int[]> packages = new LinkedHashMap<>();
        List<FileStats> files = new ArrayList<>();
        List<ClassStats> classes = new ArrayList<>();
        List<ClassStats> mainClasses = new ArrayList<>();
        Set<String> classNames = new HashSet<>();
        int totalLines = 0;
        int testFiles = 0;
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String uri = source.getKey();
            if (WorkspaceModel.isJavaUri(uri)) {
                continue;
            }
            int lines = lineCount(source.getValue());
            totalLines += lines;
            boolean test = isTest(root, uri);
            if (test) {
                testFiles++;
            }
            ModuleNode module = modules.get(uri);
            int fileClasses = 0;
            int fileMembers = 0;
            if (module != null) {
                for (ClassNode classNode : module.getClasses()) {
                    if (CodeSearch.isGenerated(classNode) || isAnonymous(classNode)) {
                        continue;
                    }
                    int members = members(classNode);
                    fileMembers += members;
                    classNames.add(classNode.getNameWithoutPackage().replace('$', '.'));
                    Range range = AstRanges.rangeOf(classNode);
                    if (classNode.isScript() || range == null) {
                        continue;
                    }
                    fileClasses++;
                    ClassStats stats = new ClassStats(classNode.getName(), uri, range.end().line() - range.start().line() + 1,
                            members);
                    classes.add(stats);
                    if (!test && classNode.getOuterClass() == null && !classNode.isInterface()
                            && !classNode.isAnnotationDefinition()) {
                        mainClasses.add(stats);
                    }
                }
            }
            files.add(new FileStats(uri, lines, fileClasses, fileMembers));
            int[] totals = packages.computeIfAbsent(packageOf(module, source.getValue()), k -> new int[3]);
            totals[0]++;
            totals[1] += fileClasses;
            totals[2] += lines;
        }
        List<PackageStats> packageStats = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : packages.entrySet()) {
            packageStats.add(new PackageStats(entry.getKey(), entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]));
        }
        packageStats.sort(Comparator.comparingInt(PackageStats::lines).reversed().thenComparing(PackageStats::name));
        files.sort(Comparator.comparingInt(FileStats::lines).reversed().thenComparing(FileStats::uri));
        classes.sort(Comparator.comparingInt(ClassStats::lines).reversed().thenComparing(ClassStats::name));
        mainClasses.sort(Comparator.comparingInt(ClassStats::lines).reversed().thenComparing(ClassStats::name));
        List<String> untested = new ArrayList<>();
        for (ClassStats stats : mainClasses) {
            String simple = simpleName(stats.name());
            if (TEST_SUFFIXES.stream().noneMatch(suffix -> classNames.contains(simple + suffix))) {
                untested.add(stats.name());
                if (untested.size() >= TOP) {
                    break;
                }
            }
        }
        return new Overview(files.size(), totalLines, packageStats, top(files), top(classes), testFiles, untested);
    }

    private static <T> List<T> top(List<T> list) {
        return List.copyOf(list.subList(0, Math.min(TOP, list.size())));
    }

    /** Lines of a text, not counting the empty remainder after a final line break. */
    public static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && i + 1 < text.length()) {
                lines++;
            }
        }
        return lines;
    }

    /** True for a document under a test source root ({@code test}, {@code tests}, {@code *Test}) or named like a test. */
    static boolean isTest(Path root, String uri) {
        Path path = WorkspaceModel.toPath(uri);
        if (path == null) {
            return false;
        }
        Path relative = root != null && path.startsWith(root) ? root.relativize(path) : path;
        int count = relative.getNameCount();
        for (int i = 0; i < count - 1; i++) {
            String segment = relative.getName(i).toString();
            if (segment.equals("test") || segment.equals("tests") || segment.endsWith("Test")) {
                return true;
            }
        }
        String file = relative.getFileName() == null ? "" : relative.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;
        return TEST_SUFFIXES.stream().anyMatch(base::endsWith);
    }

    static String packageOf(ModuleNode module, String text) {
        String name = module == null ? null : module.getPackageName();
        if (name == null || name.isEmpty()) {
            Matcher matcher = PACKAGE.matcher(text == null ? "" : text);
            name = module == null && matcher.find() ? matcher.group(1) : null;
        }
        if (name == null || name.isEmpty()) {
            return DEFAULT_PACKAGE;
        }
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    private static boolean isAnonymous(ClassNode classNode) {
        return classNode instanceof InnerClassNode inner && inner.isAnonymous();
    }

    /** Positioned methods, constructors, fields and properties declared by the class. */
    static int members(ClassNode classNode) {
        int members = 0;
        for (MethodNode method : classNode.getMethods()) {
            if (!method.isSynthetic() && !method.isScriptBody() && AstRanges.rangeOf(method) != null) {
                members++;
            }
        }
        for (MethodNode constructor : classNode.getDeclaredConstructors()) {
            if (AstRanges.rangeOf(constructor) != null) {
                members++;
            }
        }
        for (FieldNode field : classNode.getFields()) {
            if (!field.isSynthetic() && AstRanges.rangeOf(field) != null && classNode.getProperty(field.getName()) == null) {
                members++;
            }
        }
        members += (int) classNode.getProperties().stream().filter(p -> AstRanges.rangeOf(p) != null).count();
        return members;
    }

    private static String simpleName(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        return name.replace('$', '.');
    }
}
