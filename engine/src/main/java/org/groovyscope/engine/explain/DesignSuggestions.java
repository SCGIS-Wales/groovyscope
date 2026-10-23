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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.groovyscope.engine.semantics.TypeNames;

/**
 * One refactor per design finding, each with the edit tool call that performs it, written as text for an agent to
 * fill in and send: {@code move_member}, {@code extract_method}, {@code change_signature} or {@code edit_symbol}.
 * Names in angle brackets are for the agent to choose.
 */
public final class DesignSuggestions {

    /** A refactor: what kind, why in one line, and the tool call. */
    public record Suggestion(String kind, String message, String tool) {
    }

    private DesignSuggestions() {
    }

    /** The suggestions for a class's design; {@code path} is its document relative to the workspace root. */
    public static List<Suggestion> of(DesignFacts.ClassDesign design, String path) {
        List<Suggestion> out = new ArrayList<>();
        String packagePrefix = design.name().contains(".")
                ? design.name().substring(0, design.name().lastIndexOf('.') + 1) : "";
        split(design, packagePrefix, out);
        for (DesignFacts.Envy envy : design.featureEnvy()) {
            out.add(new Suggestion("move", plain(envy.method()) + " uses " + DesignFacts.simpleName(envy.target())
                    + "'s state " + envy.targetUses() + " times and its own " + envy.ownUses() + ": move it there",
                    moveMember(design.name() + "." + plain(envy.method()), envy.target())));
        }
        if (design.dataOwner() != null && design.dataOwner().method() != null) {
            DesignFacts.DataOwner owner = design.dataOwner();
            out.add(new Suggestion("move", design.simpleName() + " only holds data, and "
                    + DesignFacts.simpleName(owner.owner()) + " reads and writes it: move "
                    + plain(owner.method()) + " into " + design.simpleName(),
                    moveMember(owner.owner() + "." + plain(owner.method()), design.name())));
        }
        for (DesignFacts.LocalCandidate local : design.fieldsUsedByOneMethod()) {
            out.add(new Suggestion("local", "field " + local.field() + " is only written and read by "
                    + plain(local.method()) + ": make it a local variable there",
                    editSymbol(path, design.simpleName() + "." + plain(local.method()), "replace_body",
                            "<the body, declaring " + local.field() + " as a local>") + ", then "
                            + editSymbol(path, design.simpleName() + "." + local.field(), "delete", null)));
        }
        for (DesignFacts.SharedParameters shared : design.sharedParameterLists()) {
            out.add(new Suggestion("parameterObject", String.join(", ", shared.methods()) + " all take ("
                    + String.join(", ", shared.parameters()) + "): pass them as one object",
                    changeSignature(design.name() + "." + plain(shared.methods().get(0)),
                            grouped(shared.nodes().get(0), Set.copyOf(shared.parameters())))));
        }
        for (DesignFacts.LongParameters list : design.longParameterLists()) {
            Set<String> tail = new LinkedHashSet<>(list.parameters().subList(Math.min(list.parameters().size(), 2),
                    list.parameters().size()));
            out.add(new Suggestion("parameterObject", plain(list.method()) + " takes " + list.parameters().size()
                    + " parameters: pass the ones that travel together as one object",
                    changeSignature(design.name() + "." + plain(list.method()), grouped(list.node(), tail))));
        }
        for (DesignFacts.PrimitiveObsession obsession : design.primitiveObsession()) {
            List<String> parameters = new ArrayList<>();
            for (Parameter parameter : obsession.node().getParameters()) {
                String type = obsession.parameters().contains(parameter.getName()) ? capitalised(parameter.getName())
                        : typeOf(parameter);
                parameters.add(parameter(parameter.getName(), type));
            }
            out.add(new Suggestion("valueType", plain(obsession.method()) + " takes "
                    + String.join(", ", obsession.parameters()) + " as Strings: give each its own type",
                    changeSignature(design.name() + "." + plain(obsession.method()), parameters)));
        }
        for (DesignFacts.TypeSwitch typeSwitch : design.typeSwitches()) {
            DesignFacts.Branch first = typeSwitch.branches().get(0);
            List<String> types = new ArrayList<>();
            for (DesignFacts.Branch branch : typeSwitch.branches()) {
                types.add(branch.type());
            }
            out.add(new Suggestion("polymorphism", plain(typeSwitch.method()) + " branches on the type of "
                    + typeSwitch.subject() + " (" + String.join(", ", types) + "): extract each branch, then "
                    + (typeSwitch.workspaceTypes() ? "move it to its type as one method they all have"
                            : "call it from a visitor or a map from type to handler"),
                    "extract_method(path: " + quote(path) + ", startLine: " + first.startLine() + ", endLine: "
                            + first.endLine() + ", name: " + quote(plain(typeSwitch.method()) + "For" + first.type())
                            + ")"));
        }
        for (DesignFacts.Exposed exposed : design.publicMutableFields()) {
            out.add("field".equals(exposed.kind())
                    ? new Suggestion("encapsulate", "public field " + exposed.name() + " can be changed by any caller:"
                            + " make it a property", editSymbol(path, design.simpleName() + "." + exposed.name(),
                                    "replace", exposed.type() + " " + exposed.name()))
                    : new Suggestion("encapsulate", plain(exposed.name()) + " hands out the mutable field "
                            + exposed.field() + ": return an unmodifiable view", editSymbol(path,
                                    design.simpleName() + "." + plain(exposed.name()), "replace_body",
                                    "return " + exposed.field() + ".asImmutable()")));
        }
        for (DesignFacts.StaticState state : design.staticMutableState()) {
            out.add(new Suggestion("immutable", "static " + state.name() + " is shared by every instance and thread:"
                    + " make it final and unmodifiable, or an instance field",
                    editSymbol(path, design.simpleName() + "." + state.name(), "replace",
                            "<static final, unmodifiable declaration of " + state.name() + ">")));
        }
        for (DesignFacts.Stateless stateless : design.statelessMethods()) {
            out.add(stateless.target() != null
                    ? new Suggestion("move", plain(stateless.method()) + " uses no state of "
                            + design.simpleName() + " and works on " + DesignFacts.simpleName(stateless.target())
                            + ": move it there", moveMember(design.name() + "." + plain(stateless.method()),
                                    stateless.target()))
                    : new Suggestion("static", plain(stateless.method()) + " uses no instance state: make it static",
                            editSymbol(path, design.simpleName() + "." + plain(stateless.method()), "replace",
                                    "<the method, declared static>")));
        }
        return out;
    }

    /**
     * Groups of two methods or more sharing nothing with the largest one: candidates for classes of their own. A
     * single method alone is left to the other suggestions (it uses no state, or one field only it needs).
     */
    private static void split(DesignFacts.ClassDesign design, String packagePrefix, List<Suggestion> out) {
        List<DesignFacts.Group> groups = new ArrayList<>();
        for (DesignFacts.Group group : design.cohesion().groups()) {
            if (group.methods().size() >= 2) {
                groups.add(group);
            }
        }
        for (int i = 1; i < groups.size(); i++) {
            DesignFacts.Group group = groups.get(i);
            out.add(new Suggestion("split", String.join(", ", group.methods()) + " share no field and no call with "
                    + String.join(", ", groups.get(0).methods()) + ": move them to a class of their own"
                    + (group.fields().isEmpty() ? "" : ", with " + String.join(", ", group.fields())),
                    moveMember(design.name() + "." + plain(group.methods().get(0)), packagePrefix + "<NewClass>")));
        }
        if (groups.size() > 1) {
            return;
        }
        for (DesignFacts.Group group : design.cohesion().separable()) {
            out.add(new Suggestion("split", String.join(", ", group.methods()) + " share no field with the rest of "
                    + design.simpleName() + ", which only calls them: move them to a class of their own, with "
                    + String.join(", ", group.fields()),
                    moveMember(design.name() + "." + plain(group.methods().get(0)), packagePrefix + "<NewClass>")));
        }
    }

    /** A method's parameters with {@code together} replaced by one parameter object, where the first of them was. */
    private static List<String> grouped(MethodNode method, Set<String> together) {
        List<String> parameters = new ArrayList<>();
        boolean placed = false;
        for (Parameter parameter : method.getParameters()) {
            if (together.contains(typeOf(parameter) + " " + parameter.getName())) {
                if (!placed) {
                    parameters.add(parameter("<object>", "<NewType>"));
                    placed = true;
                }
            } else {
                parameters.add(parameter(parameter.getName(), typeOf(parameter)));
            }
        }
        return parameters;
    }

    private static String typeOf(Parameter parameter) {
        return parameter.isDynamicTyped() ? "def" : TypeNames.simple(parameter.getType());
    }

    private static String plain(String label) {
        int bracket = label.indexOf('(');
        return bracket > 0 ? label.substring(0, bracket) : label;
    }

    private static String capitalised(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String parameter(String name, String type) {
        return "{name: " + quote(name) + ", type: " + quote(type) + "}";
    }

    private static String moveMember(String symbol, String toClass) {
        return "move_member(symbol: " + quote(symbol) + ", toClass: " + quote(toClass) + ")";
    }

    private static String changeSignature(String symbol, List<String> parameters) {
        return "change_signature(symbol: " + quote(symbol) + ", parameters: [" + String.join(", ", parameters) + "])";
    }

    private static String editSymbol(String path, String symbol, String operation, String text) {
        return "edit_symbol(path: " + quote(path) + ", symbol: " + quote(symbol) + ", operation: " + quote(operation)
                + (text == null ? "" : ", text: " + quote(text)) + ")";
    }

    private static String quote(String text) {
        return "\"" + (text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }
}
