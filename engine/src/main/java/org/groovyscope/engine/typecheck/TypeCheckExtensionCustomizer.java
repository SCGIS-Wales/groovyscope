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
package org.groovyscope.engine.typecheck;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

/**
 * Adds type-checking extensions (the Groovy 6 checkers) to every {@code @CompileStatic} and
 * {@code @TypeChecked} annotation already present in the workspace, so static code gets the extra checks
 * without forcing static typing onto dynamic code.
 */
public final class TypeCheckExtensionCustomizer extends CompilationCustomizer {

    private static final Set<String> TARGET_ANNOTATIONS = Set.of(
            "groovy.transform.CompileStatic", "CompileStatic", "groovy.transform.TypeChecked", "TypeChecked");

    private final List<String> extensions;

    public TypeCheckExtensionCustomizer(List<String> extensions) {
        super(CompilePhase.CONVERSION);
        this.extensions = List.copyOf(extensions);
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) throws CompilationFailedException {
        if (extensions.isEmpty()) {
            return;
        }
        amend(classNode);
        for (MethodNode method : classNode.getMethods()) {
            amend(method);
        }
    }

    void amend(AnnotatedNode node) {
        for (AnnotationNode annotation : node.getAnnotations()) {
            if (!TARGET_ANNOTATIONS.contains(annotation.getClassNode().getName())) {
                continue;
            }
            Set<String> names = new LinkedHashSet<>(existing(annotation.getMember("extensions")));
            names.addAll(extensions);
            ListExpression list = new ListExpression();
            for (String name : names) {
                list.addExpression(new ConstantExpression(name));
            }
            annotation.setMember("extensions", list);
        }
    }

    static List<String> existing(Expression member) {
        List<String> names = new ArrayList<>();
        if (member instanceof ConstantExpression constant && constant.getValue() != null) {
            names.add(constant.getValue().toString());
        } else if (member instanceof ListExpression list) {
            for (Expression element : list.getExpressions()) {
                if (element instanceof ConstantExpression constant && constant.getValue() != null) {
                    names.add(constant.getValue().toString());
                }
            }
        }
        return names;
    }
}
