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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.groovyscope.engine.config.EngineConfig;
import org.groovyscope.engine.explain.DesignFacts;
import org.groovyscope.engine.explain.DesignIndex;
import org.groovyscope.engine.index.AstRanges;
import org.groovyscope.engine.model.Range;

/**
 * Design checks GS090 to GS097 (docs/practices.md, section 22), on the facts {@link DesignFacts} reads from the
 * resolved tree: a god class, feature envy, a long parameter list, a chain of type tests, state anyone may change, an
 * anemic data class, primitive obsession and a method that needs no instance. Main sources only; tests, Spock
 * specifications, scripts, interfaces and anonymous classes are left alone. A class finding sits on the class name, a
 * member finding on the member's name, and each is reported once.
 */
final class DesignChecks {

    private DesignChecks() {
    }

    static void find(PracticeContext context) {
        if (context.test() || context.gradle()) {
            return;
        }
        EngineConfig.Design limits = context.config().practices().design();
        DesignIndex index = null;
        for (ClassNode classNode : context.module().getClasses()) {
            if (classNode.getLineNumber() <= 0 || classNode.isScript() || classNode.isInterface()
                    || classNode.getName().contains("$_") || classNode.getName().contains("$Trait$")
                    || classNode instanceof InnerClassNode inner && inner.isAnonymous()
                    || context.isTestClass(classNode.getNameWithoutPackage())
                    || PracticeContext.isSpecification(classNode)) {
                continue;
            }
            if (index == null) {
                index = DesignIndex.of(context.module());
            }
            report(context, classNode, DesignFacts.of(classNode, index, limits), limits);
        }
    }

    private static void report(PracticeContext context, ClassNode classNode, DesignFacts.ClassDesign design,
            EngineConfig.Design limits) {
        godClass(context, classNode, design, limits);
        for (DesignFacts.Envy envy : design.featureEnvy()) {
            String target = DesignFacts.simpleName(envy.target());
            at(context, "GS091", envy.node(), envy.method() + " uses " + target + "'s fields and getters "
                    + envy.targetUses() + " times and its own class's " + envy.ownUses() + ": move it to " + target
                    + " (move_member)");
        }
        for (DesignFacts.LongParameters list : design.longParameterLists()) {
            at(context, "GS092", list.node(), name(list.method(), design) + " takes " + list.parameters().size()
                    + " parameters (more than " + limits.maxParameters() + "): pass the ones that travel together as"
                    + " one object (change_signature)");
        }
        for (Map.Entry<ASTNode, List<DesignFacts.TypeSwitch>> entry : DesignFacts.byMethod(design.typeSwitches())
                .entrySet()) {
            DesignFacts.TypeSwitch first = entry.getValue().get(0);
            Set<String> types = new LinkedHashSet<>();
            for (DesignFacts.Branch branch : first.branches()) {
                types.add(branch.type());
            }
            at(context, "GS093", entry.getKey(), first.method() + " branches on the type of " + first.subject() + " "
                    + first.branches().size() + " times (" + String.join(", ", types) + "): "
                    + (first.workspaceTypes() ? "give the types one method of their own and call it"
                            : "a visitor, or a map from type to handler, keeps each case with the others of its type"));
        }
        for (DesignFacts.Exposed exposed : design.publicMutableFields()) {
            at(context, "GS094", exposed.node(), "field".equals(exposed.kind())
                    ? "public field " + exposed.name() + " can be changed by any caller: make it final, or a property"
                            + " (drop public)"
                    : exposed.name() + " returns the mutable " + exposed.type() + " field " + exposed.field()
                            + " itself: return a copy or an unmodifiable view (" + exposed.field()
                            + ".asImmutable())");
        }
        if (design.dataOwner() != null) {
            String owner = DesignFacts.simpleName(design.dataOwner().owner());
            at(context, "GS095", classNode, design.simpleName() + " only holds data: " + owner + " reads and"
                    + " writes its fields and holds the logic over them; move that logic into " + design.simpleName()
                    + " (move_member)");
        }
        for (DesignFacts.PrimitiveObsession obsession : design.primitiveObsession()) {
            at(context, "GS096", obsession.node(), name(obsession.method(), design) + " takes "
                    + obsession.parameters().size() + " String parameters named as identifiers ("
                    + String.join(", ", obsession.parameters()) + "): give them types of their own, so they cannot"
                    + " be swapped");
        }
        for (DesignFacts.Stateless stateless : design.statelessMethods()) {
            at(context, "GS097", stateless.node(), stateless.method() + " reads no field, calls no instance method"
                    + " and does not use this: make it static" + (stateless.target() == null ? "" : ", or move it to "
                            + DesignFacts.simpleName(stateless.target())
                            + ", whose objects it works on (move_member)"));
        }
    }

    /** GS090: over any of the size limits, each one named with its number. */
    private static void godClass(PracticeContext context, ClassNode classNode, DesignFacts.ClassDesign design,
            EngineConfig.Design limits) {
        DesignFacts.Size size = design.size();
        List<String> over = new ArrayList<>();
        if (size.methods() > limits.maxMethods()) {
            over.add(size.methods() + " methods (more than " + limits.maxMethods() + ")");
        }
        if (size.fields() > limits.maxFields()) {
            over.add(size.fields() + " fields (more than " + limits.maxFields() + ")");
        }
        if (size.lines() > limits.maxLines()) {
            over.add(size.lines() + " lines (more than " + limits.maxLines() + ")");
        }
        if (!over.isEmpty()) {
            at(context, "GS090", classNode, design.simpleName() + " has " + String.join(", ", over)
                    + ": split it by responsibility (class_design lists its groups of methods)");
        }
    }

    /** A constructor's label names its class. */
    private static String name(String method, DesignFacts.ClassDesign design) {
        return method.startsWith("<init>") ? "the constructor of " + design.simpleName() : method;
    }

    /** Reports at a declaration's name, or at the whole declaration when the name cannot be placed. */
    private static void at(PracticeContext context, String code, ASTNode node, String message) {
        Range range = AstRanges.rangeOf(node);
        if (range == null) {
            return;
        }
        context.report(code, AstRanges.nameRange(node, range, context.lines()), message, null, List.of());
    }
}
