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

import java.util.ArrayList;
import java.util.List;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.groovyscope.engine.model.Diagnostic.Severity;
import org.groovyscope.engine.model.TextEdit;
import org.groovyscope.engine.semantics.Logging;

/** GS005: a GString message given to a proven logger, built even when the level is off. */
final class LoggingChecks {

    private LoggingChecks() {
    }

    static void call(CoreScan scan, MethodCallExpression call, String name) {
        if (!Logging.LEVELS.contains(name) || !(call.getArguments() instanceof TupleExpression args)
                || args.getExpressions().isEmpty() || !(args.getExpression(0) instanceof GStringExpression gstring)) {
            return;
        }
        Expression receiver = call.getObjectExpression();
        String annotated = PracticeContext.annotatedLogger(receiver, scan.currentClass);
        String library = PracticeContext.loggerLibrary(receiver, scan.currentClass);
        if (library == null) {
            return; // a receiver merely named log is not proven to be a logger
        }
        StringBuilder message = new StringBuilder("'");
        List<String> arguments = new ArrayList<>();
        boolean fixable = args.getExpressions().size() == 1;
        for (int i = 0; i < gstring.getStrings().size(); i++) {
            Object part = gstring.getStrings().get(i).getValue();
            String literal = part == null ? "" : part.toString();
            if (literal.contains("'") || literal.contains("\\") || literal.contains("{}") || literal.contains("\n")) {
                fixable = false;
            }
            message.append(literal);
            if (i < gstring.getValues().size()) {
                Expression interpolated = gstring.getValue(i);
                String value = scan.context.source(interpolated);
                // A lazy ${-> x} would log the closure itself, and SLF4J takes a trailing Throwable as the
                // exception (a stack trace, and a literal {} left in the message).
                if (value == null || interpolated instanceof ClosureExpression
                        || i == gstring.getValues().size() - 1 && mayBeThrowable(scan, interpolated)) {
                    fixable = false;
                }
                arguments.add(value);
                message.append("{}");
            }
        }
        message.append("'");
        boolean placeholders = Logging.PLACEHOLDER_LIBRARIES.contains(library);
        List<TextEdit> fix = fixable && placeholders
                ? List.of(scan.context.replace(gstring, message + (arguments.isEmpty() ? ""
                        : ", " + String.join(", ", arguments))))
                : List.of();
        if (annotated != null) {
            // The logging AST transformation wraps the call in a level check, so nothing is built in vain.
            scan.context.reportAtMost(Severity.INFORMATION, "GS005", gstring, "the logging annotation already skips "
                    + "the call when " + name + " is disabled; " + (placeholders
                            ? "{} placeholders keep the message template constant and interpolated values (such as "
                                    + "secrets) out of it"
                            : "keep interpolated values (such as secrets) out of the message"),
                    placeholders ? "Use {} placeholders" : null, fix);
            return;
        }
        scan.context.report("GS005", gstring, "the GString is built even when " + name + " is disabled"
                + (placeholders ? "" : "; guard the call with the level check"),
                placeholders ? "Use {} placeholders" : null, fix);
    }

    /** True unless the value's declared type proves it is not a Throwable (a catch parameter always may be). */
    private static boolean mayBeThrowable(CoreScan scan, Expression value) {
        if (value instanceof VariableExpression variable && scan.caught.contains(variable.getAccessedVariable())) {
            return true;
        }
        ClassNode type = PracticeFinder.typeOf(value);
        if (type == null) {
            return false;
        }
        String name = type.getName();
        return type.isDerivedFrom(ClassHelper.make(Throwable.class)) || name.endsWith("Exception")
                || name.endsWith("Error") || name.endsWith("Throwable");
    }
}
