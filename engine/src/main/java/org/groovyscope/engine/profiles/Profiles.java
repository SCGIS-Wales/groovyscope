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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.groovyscope.engine.index.TextLines;
import org.groovyscope.engine.model.Range;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.workspace.WorkspaceModel;

/** Which profile governs a document, and which parts of it automatic edits must not touch. */
public final class Profiles {

    private Profiles() {
    }

    /** The first profile governing a document, if any. */
    public static Optional<Profile> governing(List<Profile> profiles, Path root, String uri, ModuleNode module) {
        if (profiles.isEmpty() || WorkspaceModel.isJavaUri(uri)) {
            return Optional.empty();
        }
        Path file = WorkspaceModel.toPath(uri);
        if (file == null) {
            return Optional.empty();
        }
        Path top = root.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(top)) {
            return Optional.empty();
        }
        String relative = top.relativize(absolute).toString();
        List<String> imports = new ArrayList<>();
        if (module != null) {
            for (ImportNode importNode : module.getImports()) {
                imports.add(importNode.getClassName());
            }
            for (ImportNode importNode : module.getStarImports()) {
                imports.add(importNode.getPackageName());
            }
        }
        for (Profile profile : profiles) {
            if (profile.governs(relative, imports) && hasMarker(profile, top, absolute)) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    /** True when the profile needs no marker, or one of its marker files is in the file's directory or above. */
    static boolean hasMarker(Profile profile, Path root, Path file) {
        if (profile.activation().markers().isEmpty()) {
            return true;
        }
        Path directory = file.getParent();
        while (directory != null && directory.startsWith(root)) {
            for (String marker : profile.activation().markers()) {
                if (Files.exists(directory.resolve(marker))) {
                    return true;
                }
            }
            directory = directory.getParent();
        }
        return false;
    }

    /** Ranges automatic edits must leave alone: protected methods and marked regions. */
    public static List<Range> protectedRanges(Profile profile, ModuleNode module, TextLines lines) {
        List<Range> ranges = new ArrayList<>();
        Profile.Protected rules = profile.protectedRegions();
        if (module != null && !rules.methods().isEmpty()) {
            for (ClassNode classNode : module.getClasses()) {
                for (MethodNode method : classNode.getMethods()) {
                    if (rules.methods().contains(method.getName()) && method.getLineNumber() > 0) {
                        ranges.add(Range.of(method.getLineNumber() - 1, 0, method.getLastLineNumber() - 1,
                                Math.max(0, method.getLastColumnNumber() - 1)));
                    }
                }
            }
        }
        if (!rules.begin().isBlank() && !rules.end().isBlank()) {
            int start = -1;
            for (int i = 0; i < lines.lineCount(); i++) {
                String line = lines.line(i);
                if (start < 0 && line.contains(rules.begin())) {
                    start = i;
                } else if (start >= 0 && line.contains(rules.end())) {
                    ranges.add(Range.of(start, 0, i, line.length()));
                    start = -1;
                }
            }
        }
        return ranges;
    }

    /** True when an edit touches any protected range. */
    public static boolean touchesProtected(TextEdit edit, List<Range> ranges) {
        for (Range range : ranges) {
            boolean before = edit.range().end().line() < range.start().line();
            boolean after = edit.range().start().line() > range.end().line();
            if (!before && !after) {
                return true;
            }
        }
        return false;
    }
}
