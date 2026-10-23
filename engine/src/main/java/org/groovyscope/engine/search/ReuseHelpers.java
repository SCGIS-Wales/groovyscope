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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.transform.trait.Traits;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.explain.CodeFacts;
import org.groovyscope.engine.semantics.TypeNames;
import org.groovyscope.engine.workspace.TestSources;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * The utilities a class or one of its members could call instead of writing them again: what {@code explainCode}
 * lists as {@code helpers}, so an agent reading code before changing it sees what already exists around it.
 */
public final class ReuseHelpers {

    /** Supertypes walked from one class at most; hierarchies deeper than this are not real code. */
    static final int MAX_SUPERTYPES = 32;

    private ReuseHelpers() {
    }

    /**
     * A method a class or member could call.
     *
     * @param symbol {@code pkg.Class.method}
     * @param signature the method's signature as the outline shows it
     * @param line its first line (zero-based, as {@code explainCode} counts lines)
     * @param uri the document declaring it
     */
    public record Helper(String symbol, String signature, int line, String uri) {
    }

    /**
     * The utilities {@code owner}, or its member {@code self}, could call: the static methods and small pure
     * instance methods of its own class, the non-private static and small pure instance methods of its workspace
     * superclasses and traits, and the non-private static methods of the other classes of its package (test classes
     * only when the owner is one). Small means at most {@code reuse.helperMaxLines} lines; pure means it changes no
     * field and writes nothing outside ({@link CodeFacts#stateChanges}). Instance methods of other classes need an
     * instance, so they are not listed.
     *
     * @param owner the class
     * @param self the member explained, left out; {@code null} for a class
     * @param modules the snapshot's trees by document
     * @param uriOf the document declaring a class, or {@code null} outside the workspace
     * @param settings the {@code reuse.*} thresholds
     * @return at most {@code reuse.helpersLimit} helpers: the class's own first, then its supertypes', then the
     *     package's, each by name
     */
    public static List<Helper> helpers(ClassNode owner, MethodNode self, Map<String, ModuleNode> modules,
            Function<ClassNode, String> uriOf, EngineConfig.Reuse settings) {
        List<Helper> helpers = new ArrayList<>();
        Set<MethodNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (self != null) {
            seen.add(self);
        }
        String ownerUri = uriOf.apply(owner);
        add(helpers, seen, owner, ownerUri, true, true, settings);
        Set<ClassNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(owner);
        for (ClassNode type : workspaceSupertypes(owner)) {
            if (visited.add(type)) {
                add(helpers, seen, type, uriOf.apply(type), false, true, settings);
            }
        }
        boolean ownerIsTest = ownerUri != null && TestSources.isTest(ownerUri, owner.getNameWithoutPackage());
        List<String> uris = new ArrayList<>(modules.keySet());
        Collections.sort(uris);
        for (String uri : uris) {
            if (WorkspaceModel.isJavaUri(uri)) {
                continue;
            }
            for (ClassNode type : modules.get(uri).getClasses()) {
                if (Objects.equals(owner.getPackageName(), type.getPackageName()) && !CodeSearch.isGenerated(type)
                        && (ownerIsTest || !TestSources.isTest(uri, type.getNameWithoutPackage()))
                        && visited.add(type)) {
                    add(helpers, seen, type, uri, false, false, settings);
                }
            }
        }
        return helpers.size() > settings.helpersLimit() ? List.copyOf(helpers.subList(0, settings.helpersLimit()))
                : helpers;
    }

    private static void add(List<Helper> helpers, Set<MethodNode> seen, ClassNode type, String uri, boolean own,
            boolean instances, EngineConfig.Reuse settings) {
        if (uri == null) {
            return;
        }
        List<MethodNode> methods = new ArrayList<>(type.getMethods());
        methods.sort(Comparator.comparing(MethodNode::getName).thenComparingInt(MethodNode::getLineNumber));
        for (MethodNode method : methods) {
            if (method.isSynthetic() || method.getLineNumber() < 1 || method.isScriptBody() || method.isAbstract()
                    || method.getCode() == null || !own && method.isPrivate() || !seen.add(method)) {
                continue;
            }
            boolean small = method.getLastLineNumber() - method.getLineNumber() + 1 <= settings.helperMaxLines();
            if (method.isStatic() || instances && small && CodeFacts.stateChanges(method).isEmpty()) {
                helpers.add(new Helper(ReuseFinder.className(type) + "." + method.getName(),
                        TypeNames.signature(method), method.getLineNumber() - 1, uri));
            }
        }
    }

    /** The superclasses and traits of a class that the workspace declares, nearest first. */
    public static List<ClassNode> workspaceSupertypes(ClassNode type) {
        List<ClassNode> found = new ArrayList<>();
        Set<ClassNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ClassNode> queue = new ArrayList<>();
        queue.add(type);
        while (!queue.isEmpty() && visited.size() < MAX_SUPERTYPES) {
            for (ClassNode next : direct(queue.remove(0))) {
                if (next.isPrimaryClassNode() && next != type && visited.add(next)) {
                    if (!next.isInterface() || Traits.isTrait(next)) {
                        found.add(next);
                    }
                    queue.add(next);
                }
            }
        }
        return found;
    }

    /** The direct superclass and interfaces of a class, redirected to their declarations. */
    static List<ClassNode> direct(ClassNode type) {
        List<ClassNode> next = new ArrayList<>();
        if (type.getSuperClass() != null) {
            next.add(type.getSuperClass().redirect());
        }
        for (ClassNode iface : type.getInterfaces()) {
            next.add(iface.redirect());
        }
        return next;
    }
}
