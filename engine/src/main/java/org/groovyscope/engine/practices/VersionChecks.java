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

import java.util.List;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;

/** GS010: a Groovy 6 construct in code that must still compile on Groovy 5 ({@code groovy.compat}). */
final class VersionChecks {

    private VersionChecks() {
    }

    private static boolean groovy5(CoreScan scan) {
        return scan.context.config().groovy().mustCompileOnGroovy5();
    }

    static void importNode(CoreScan scan, ImportNode importNode) {
        String name = importNode.getClassName() != null ? importNode.getClassName() : importNode.getPackageName();
        if (name != null && name.startsWith("groovy.typecheckers") && groovy5(scan)) {
            scan.context.report("GS010", importNode, "groovy.typecheckers is Groovy 6 only, but groovy.compat "
                    + "includes 5.x", null, List.of());
        }
    }

    static void annotation(CoreScan scan, AnnotationNode annotation, String name) {
        if (name.equals("SafeRegex") && groovy5(scan)) {
            scan.context.report("GS010", annotation, "@SafeRegex is Groovy 6 only, but groovy.compat includes 5.x",
                    null, List.of());
        }
        Expression extensions = annotation.getMember("extensions");
        if (extensions != null && extensions.getText().contains("groovy.typecheckers") && groovy5(scan)) {
            scan.context.report("GS010", annotation, "groovy.typecheckers extensions are Groovy 6 only", null,
                    List.of());
        }
    }

    static void declaration(CoreScan scan, DeclarationExpression expression) {
        String text = scan.context.source(expression);
        if (text != null && text.stripLeading().startsWith("val ") && groovy5(scan)) {
            scan.context.report("GS010", expression, "val is Groovy 6 only, but groovy.compat includes 5.x", null,
                    List.of());
        }
    }

    static void copyWith(CoreScan scan, MethodCallExpression call, String name) {
        if (!"copyWith".equals(name) || !groovy5(scan) || !(call.getArguments() instanceof TupleExpression args)) {
            return;
        }
        for (Expression argument : args.getExpressions()) {
            if (argument instanceof MapExpression map) {
                for (MapEntryExpression entry : map.getMapEntryExpressions()) {
                    if (entry.getKeyExpression() instanceof ConstantExpression key
                            && String.valueOf(key.getValue()).contains(".")) {
                        scan.context.report("GS010", call, "nested copyWith paths are Groovy 6 only", null, List.of());
                        return;
                    }
                }
            }
        }
    }
}
