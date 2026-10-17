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
package org.groovyscope.engine.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Walks a directory tree without descending into excluded directories ({@code .git}, the workspace exclude
 * globs, any extra names the caller skips) and without failing on what it cannot read: unreadable directories
 * and files are reported once on stderr and skipped, so one bad entry never aborts a refresh.
 */
public final class FileWalker {

    /** Directory names never walked. */
    public static final Set<String> ALWAYS_SKIPPED = Set.of(".git");

    private static final int REPORT_LIMIT = 10_000;
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private FileWalker() {
    }

    /**
     * Regular files under {@code start} accepted by {@code wanted}, sorted.
     *
     * @param root the workspace root that exclude globs are relative to
     * @param excludes workspace exclude globs; a directory they cover entirely is not entered
     * @param skippedNames directory names not entered, in addition to {@link #ALWAYS_SKIPPED}
     * @param wanted receives each regular file and its attributes
     */
    public static List<Path> files(Path start, Path root, List<String> excludes, Set<String> skippedNames,
            BiPredicate<Path, BasicFileAttributes> wanted) {
        Path top = root.toAbsolutePath().normalize();
        Path from = start.toAbsolutePath().normalize();
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(from)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path name = dir.getFileName();
                    if (name != null && (ALWAYS_SKIPPED.contains(name.toString())
                            || skippedNames.contains(name.toString()))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (dir.startsWith(top)) {
                        Path relative = top.relativize(dir);
                        for (String glob : excludes) {
                            if (Globs.excludesDirectory(glob, relative)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    BasicFileAttributes actual = attrs;
                    if (attrs.isSymbolicLink()) {
                        // A link to a file counts only when its target lies inside the root as well.
                        actual = linkedFile(file, top);
                        if (actual == null) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    if (actual.isRegularFile() && wanted.test(file, actual)) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    reportOnce(file.toString(), "cannot read " + file + ": " + exc);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) {
                        reportOnce(dir.toString(), "cannot list " + dir + ": " + exc);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException e) {
            reportOnce(from.toString(), "cannot walk " + from + ": " + e);
        }
        Collections.sort(found);
        return found;
    }

    /** The attributes of a symbolic link's target when it is a file inside {@code root}, else {@code null}. */
    static BasicFileAttributes linkedFile(Path link, Path root) {
        try {
            Path target = link.toRealPath();
            if (!target.startsWith(WorkspaceModel.realPath(root))) {
                return null;
            }
            return Files.readAttributes(target, BasicFileAttributes.class);
        } catch (IOException e) {
            return null; // a dangling link
        }
    }

    /** Prints a message on stderr the first time {@code key} is reported; returns whether it printed. */
    public static boolean reportOnce(String key, String message) {
        if (REPORTED.size() >= REPORT_LIMIT) {
            return false;
        }
        if (REPORTED.add(key)) {
            org.groovyscope.engine.log.EngineLog.warn("%s", "" + message);
            return true;
        }
        return false;
    }
}
