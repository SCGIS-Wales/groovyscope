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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.groovyscope.engine.concurrent.Cancellation;
import org.groovyscope.engine.index.NodeEntry;
import org.groovyscope.engine.model.Confidence;
import org.groovyscope.engine.semantics.Resolution;
import org.groovyscope.engine.services.Snapshot;

/**
 * Every use of one method in the workspace, from the same resolver the call graph and rename use: the calls whose
 * target is known (STATIC or INFERRED), the uses matched by name only, and the uses that are not calls (a method
 * pointer, a property read).
 *
 * @param calls the calls whose target is known, in index order
 * @param dynamic the uses matched by name only
 * @param other the uses that are not calls
 */
record Usages(List<Call> calls, List<NodeEntry> dynamic, List<NodeEntry> other) {

    /** Index entries visited between two cancellation checks. */
    static final int CHECKPOINT_EVERY = 256;

    /**
     * One call.
     *
     * @param entry its index entry (the method name for a call with a receiver, the whole call for a static one)
     * @param call the {@link MethodCallExpression} or {@link StaticMethodCallExpression}
     */
    record Call(NodeEntry entry, Expression call) {

        String uri() {
            return entry.uri();
        }

        /** {@code Caller.groovy:12}. */
        String where() {
            return SourceText.where(entry.uri(), entry.range().start().line());
        }
    }

    /** The uses of {@code method}, walking the whole index (a cancelled request leaves part way). */
    static Usages of(Snapshot snapshot, MethodNode method) {
        List<Call> calls = new ArrayList<>();
        List<NodeEntry> dynamic = new ArrayList<>();
        List<NodeEntry> other = new ArrayList<>();
        int visited = 0;
        for (NodeEntry entry : snapshot.index().allEntries()) {
            if (++visited % CHECKPOINT_EVERY == 0) {
                Cancellation.checkpoint();
            }
            NodeEntry.Role role = entry.role();
            if (role == NodeEntry.Role.OTHER || role == NodeEntry.Role.DECLARATION
                    || role == NodeEntry.Role.TYPE_USAGE || role == NodeEntry.Role.CONSTRUCTOR_CALL) {
                continue;
            }
            Optional<Resolution> resolution = snapshot.resolver().resolve(entry);
            if (resolution.isEmpty() || !resolution.get().includes(method)) {
                continue;
            }
            if (resolution.get().confidence() == Confidence.DYNAMIC_UNKNOWN) {
                dynamic.add(entry);
            } else if (entry.role() == NodeEntry.Role.METHOD_NAME && entry.parent() instanceof MethodCallExpression) {
                calls.add(new Call(entry, (Expression) entry.parent()));
            } else if (entry.role() == NodeEntry.Role.STATIC_CALL
                    && entry.node() instanceof StaticMethodCallExpression call) {
                calls.add(new Call(entry, call));
            } else {
                other.add(entry);
            }
        }
        return new Usages(calls, dynamic, other);
    }

    /** {@code A.groovy:3, B.groovy:9} for at most ten entries. */
    static String listed(List<NodeEntry> entries) {
        List<String> names = new ArrayList<>();
        for (NodeEntry entry : entries) {
            if (names.size() == 10) {
                names.add("and " + (entries.size() - 10) + " more");
                break;
            }
            names.add(SourceText.where(entry.uri(), entry.range().start().line()));
        }
        return String.join(", ", names);
    }

    /** The refusal for uses matched by name only, worded as rename's, or {@code null} when there are none. */
    String dynamicRefusal(String what) {
        if (dynamic.isEmpty()) {
            return null;
        }
        return dynamic.size() + " call site(s) are matched by name only (dynamic dispatch): " + listed(dynamic)
                + "; " + what + " refused rather than guessed";
    }

    /** The refusal for uses that are not calls, or {@code null} when there are none. */
    String otherRefusal(String what) {
        if (other.isEmpty()) {
            return null;
        }
        return "used other than by a call (a method pointer or a property) at " + listed(other) + "; " + what
                + " refused";
    }
}
