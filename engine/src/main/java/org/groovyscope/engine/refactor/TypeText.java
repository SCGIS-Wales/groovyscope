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

import java.util.List;
import java.util.StringJoiner;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.ModuleNode;

/** Types as source text: how a module can name a type, and the import it needs to name it by its simple name. */
final class TypeText {

    /** The packages every Groovy source imports. */
    private static final List<String> DEFAULT_PACKAGES = List.of("java.lang.", "java.util.", "java.io.", "java.net.",
            "groovy.lang.", "groovy.util.");

    private TypeText() {
    }

    /**
     * {@code type} as {@code module} can write it: its simple name when the module resolves that name to it (a
     * default import, the module's package, an import), else its qualified name; generics likewise; {@code def} for
     * an unknown or dynamic type.
     */
    static String render(ClassNode type, ModuleNode module) {
        if (type == null || ClassHelper.isDynamicTyped(type)) {
            return "def";
        }
        if (type.isArray()) {
            return render(type.getComponentType(), module) + "[]";
        }
        String name = ClassHelper.isPrimitiveType(type) || type.isGenericsPlaceHolder() ? type.getUnresolvedName()
                : visible(type, module) ? simpleName(type) : canonical(type);
        GenericsType[] generics = type.getGenericsTypes();
        if (generics == null || generics.length == 0 || !type.isUsingGenerics() || type.isGenericsPlaceHolder()) {
            return name;
        }
        StringJoiner joiner = new StringJoiner(", ", "<", ">");
        for (GenericsType generic : generics) {
            if (generic.isPlaceholder()) {
                joiner.add(generic.getName());
            } else if (generic.isWildcard()) {
                joiner.add("?");
            } else {
                joiner.add(render(generic.getType(), module));
            }
        }
        return name + joiner;
    }

    /** The simple name of a class, nested classes by their own name ({@code Inner} for {@code a.Outer$Inner}). */
    static String simpleName(ClassNode type) {
        String name = type.getNameWithoutPackage();
        return name.substring(name.lastIndexOf('$') + 1);
    }

    /** The name an import or a qualified reference writes: {@code a.Outer.Inner}. */
    static String canonical(ClassNode type) {
        return type.getName().replace('$', '.');
    }

    /** The package of a class with a trailing dot, or empty for the default package. */
    static String packageOf(ClassNode type) {
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot + 1);
    }

    /** The package of a module with a trailing dot, or empty for the default package. */
    static String packageOf(ModuleNode module) {
        String name = module == null ? null : module.getPackageName();
        return name == null ? "" : name.endsWith(".") ? name : name + ".";
    }

    /** Whether {@code module} resolves the simple name of {@code type} to it. */
    static boolean visible(ClassNode type, ModuleNode module) {
        if (ClassHelper.isPrimitiveType(type)) {
            return true;
        }
        String simple = simpleName(type);
        String canonical = canonical(type);
        boolean nested = type.getName().contains("$");
        if (module != null) {
            for (ClassNode declared : module.getClasses()) {
                if (declared.getName().equals(type.getName())) {
                    return true;
                }
            }
            ImportNode imported = module.getImport(simple);
            if (imported != null) {
                return imported.getType() != null && canonical(imported.getType()).equals(canonical);
            }
            if (!nested) {
                for (ImportNode star : module.getStarImports()) {
                    if (packageOf(type).equals(star.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        if (nested) {
            return false;
        }
        String pack = packageOf(type);
        if (pack.equals(packageOf(module))) {
            return true;
        }
        return DEFAULT_PACKAGES.contains(pack) || canonical.equals("java.math.BigDecimal")
                || canonical.equals("java.math.BigInteger");
    }

    /**
     * The import {@code module} needs to write {@code type} by its simple name, or {@code null} when it needs none
     * (or a different type of that name is already imported, so the name cannot be imported).
     */
    static String importFor(ClassNode type, ModuleNode module) {
        ClassNode base = type;
        while (base.isArray()) {
            base = base.getComponentType();
        }
        if (ClassHelper.isPrimitiveType(base) || ClassHelper.isDynamicTyped(base) || base.isGenericsPlaceHolder()
                || visible(base, module)) {
            return null;
        }
        if (module != null && module.getImport(simpleName(base)) != null) {
            return null;
        }
        return "import " + canonical(base);
    }
}
