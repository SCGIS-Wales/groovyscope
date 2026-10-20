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
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.groovyscope.engine.model.TextEdit;

/**
 * GS011: Groovy's {@code .with {}} in a Spock {@code then:} or {@code expect:} block, which evaluates its comparisons
 * without asserting them.
 */
final class SpockWithCheck {

    private SpockWithCheck() {
    }

    static void expressionStatement(CoreScan scan, ExpressionStatement statement) {
        Expression expression = statement.getExpression();
        if (scan.inSpock && ("then".equals(scan.block) || "expect".equals(scan.block))
                && expression instanceof MethodCallExpression call && "with".equals(call.getMethodAsString())
                && !call.isImplicitThis() && call.getArguments() instanceof TupleExpression args
                && args.getExpressions().size() == 1 && args.getExpression(0) instanceof ClosureExpression closure) {
            String receiver = scan.context.source(call.getObjectExpression());
            String closureText = scan.context.source(closure);
            List<TextEdit> fix = receiver == null || closureText == null ? List.of()
                    : List.of(scan.context.replace(call, "with(" + receiver + ") " + closureText));
            scan.context.report("GS011", call, "Groovy's .with {} is a plain method call, so the comparisons inside it "
                    + "are evaluated but never asserted; Spock asserts each one only inside with(obj) {}",
                    "Use with(" + (receiver == null ? "obj" : receiver) + ") {}", fix);
        }
    }
}
