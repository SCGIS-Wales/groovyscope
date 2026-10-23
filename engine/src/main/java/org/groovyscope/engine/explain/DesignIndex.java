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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CompileUnit;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.groovyscope.engine.concurrent.Cancellation;

/**
 * What the design facts need to know about the rest of the workspace: which classes read and write the fields of a
 * class (for the anemic data class, GS095), and which methods subclasses declare again (an overridden method cannot
 * become static, GS097). Built once per compilation and kept while the compilation lives; it holds names only, so
 * it never keeps a compilation alive.
 */
public final class DesignIndex {

    private static final Map<CompileUnit, DesignIndex> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    /** How one class uses another's fields and accessors: reads and writes, and per method of its own. */
    public record Use(int reads, int writes, Map<String, Integer> byMethod) {
    }

    private final WeakReference<CompileUnit> unit;
    private final List<ModuleNode> modules;
    private Map<String, Set<String>> redeclared;
    private Map<String, Map<String, Use>> fieldUsers;

    private DesignIndex(CompileUnit unit, List<ModuleNode> modules) {
        this.unit = new WeakReference<>(unit);
        this.modules = modules;
    }

    /** The index of the compilation a module belongs to (only the module itself when it belongs to none). */
    public static DesignIndex of(ModuleNode module) {
        CompileUnit unit = module.getUnit();
        if (unit == null) {
            return new DesignIndex(null, List.of(module));
        }
        return CACHE.computeIfAbsent(unit, key -> new DesignIndex(key, null));
    }

    private List<ModuleNode> modules() {
        if (modules != null) {
            return modules;
        }
        CompileUnit current = unit.get();
        return current == null ? List.of() : current.getModules();
    }

    /** Every class of the compilation, closures and generated helpers left out. */
    private List<ClassNode> classes() {
        List<ClassNode> classes = new ArrayList<>();
        for (ModuleNode module : modules()) {
            for (ClassNode classNode : module.getClasses()) {
                if (classNode.getLineNumber() > 0 && !classNode.getName().contains("$_")
                        && !classNode.getName().contains("$Trait$")) {
                    classes.add(classNode);
                }
            }
        }
        return classes;
    }

    /** Whether a subclass in the workspace declares a method of that name taking that many parameters. */
    public synchronized boolean redeclaredBelow(ClassNode type, String name, int parameters) {
        if (redeclared == null) {
            redeclared = new HashMap<>();
            for (ClassNode classNode : classes()) {
                Set<String> keys = new HashSet<>();
                for (MethodNode method : classNode.getMethods()) {
                    if (!method.isStatic()) {
                        keys.add(key(method.getName(), method.getParameters().length));
                    }
                }
                Set<ClassNode> above = new HashSet<>(classNode.getAllInterfaces());
                for (ClassNode parent = classNode.getSuperClass(); parent != null; parent = parent.getSuperClass()) {
                    above.add(parent);
                }
                for (ClassNode parent : above) {
                    redeclared.computeIfAbsent(parent.getName(), k -> new HashSet<>()).addAll(keys);
                }
            }
        }
        return redeclared.getOrDefault(type.getName(), Set.of()).contains(key(name, parameters));
    }

    private static String key(String name, int parameters) {
        return name + "/" + parameters;
    }

    /**
     * The workspace classes that use a class's instance fields and accessors from their own methods, with how often
     * they read and write them; the class itself, its supertypes and subtypes left out.
     */
    public synchronized Map<String, Use> fieldUsers(String type) {
        if (fieldUsers == null) {
            Map<String, Map<String, Use>> users = new HashMap<>();
            for (ClassNode classNode : classes()) {
                Cancellation.checkpoint();
                for (MethodNode method : DesignFacts.members(classNode)) {
                    DesignScan scan = DesignScan.of(method);
                    for (Map.Entry<String, int[]> entry : scan.foreign.entrySet()) {
                        Map<String, Use> byUser = users.computeIfAbsent(entry.getKey(), k -> new TreeMap<>());
                        Use before = byUser.get(classNode.getName());
                        Map<String, Integer> byMethod = new TreeMap<>(before == null ? Map.of() : before.byMethod());
                        int[] uses = entry.getValue();
                        byMethod.merge(DesignFacts.label(method), uses[0] + uses[1], Integer::sum);
                        byUser.put(classNode.getName(), new Use((before == null ? 0 : before.reads()) + uses[0],
                                (before == null ? 0 : before.writes()) + uses[1], byMethod));
                    }
                }
            }
            fieldUsers = users;
        }
        return fieldUsers.getOrDefault(type, Map.of());
    }
}
