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
package org.groovyscope.engine.lsp;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.groovyscope.engine.model.Completion;
import org.groovyscope.engine.model.Hover;
import org.groovyscope.engine.model.SignatureHelp;
import org.groovyscope.engine.model.Symbol;

/** Translates the engine's protocol-free model into LSP4J types. The only place that knows both. */
final class Converters {

    private Converters() {
    }

    static Position toLsp(org.groovyscope.engine.model.Position position) {
        return new Position(position.line(), position.column());
    }

    static org.groovyscope.engine.model.Position fromLsp(Position position) {
        return new org.groovyscope.engine.model.Position(position.getLine(), position.getCharacter());
    }

    static Range toLsp(org.groovyscope.engine.model.Range range) {
        return new Range(toLsp(range.start()), toLsp(range.end()));
    }

    static Location toLsp(org.groovyscope.engine.model.Location location) {
        return new Location(location.uri(), toLsp(location.range()));
    }

    static TextEdit toLsp(org.groovyscope.engine.model.TextEdit edit) {
        return new TextEdit(toLsp(edit.range()), edit.newText());
    }

    static Diagnostic toLsp(org.groovyscope.engine.model.Diagnostic diagnostic) {
        Diagnostic converted = new Diagnostic(toLsp(diagnostic.range()), diagnostic.message(),
                severity(diagnostic.severity()), diagnostic.source());
        if (diagnostic.code() != null) {
            converted.setCode(diagnostic.code());
            String link = org.groovyscope.engine.practices.Explanations.link(diagnostic.code(), diagnostic.source());
            if (link != null) {
                converted.setCodeDescription(new org.eclipse.lsp4j.DiagnosticCodeDescription(link));
            }
        }
        return converted;
    }

    /**
     * A diagnostic whose {@code data} says {@code {"fixable": true}} when a quick fix exists for it, and carries the
     * finding's {@code confidence} ({@code STATIC} or {@code INFERRED}) when it has one.
     */
    static Diagnostic toLsp(org.groovyscope.engine.model.Diagnostic diagnostic, boolean fixable) {
        Diagnostic converted = toLsp(diagnostic);
        if (fixable || diagnostic.confidence() != null) {
            com.google.gson.JsonObject data = new com.google.gson.JsonObject();
            if (fixable) {
                data.addProperty("fixable", true);
            }
            if (diagnostic.confidence() != null) {
                data.addProperty("confidence", diagnostic.confidence().name());
            }
            converted.setData(data);
        }
        return converted;
    }

    static org.eclipse.lsp4j.DocumentHighlight highlight(org.groovyscope.engine.model.Highlight highlight) {
        return new org.eclipse.lsp4j.DocumentHighlight(toLsp(highlight.range()),
                highlight.write() ? org.eclipse.lsp4j.DocumentHighlightKind.Write
                        : org.eclipse.lsp4j.DocumentHighlightKind.Read);
    }

    static org.eclipse.lsp4j.FoldingRange foldingRange(org.groovyscope.engine.model.FoldingRange range) {
        org.eclipse.lsp4j.FoldingRange converted = new org.eclipse.lsp4j.FoldingRange(range.startLine(), range.endLine());
        converted.setKind(switch (range.kind()) {
            case COMMENT -> org.eclipse.lsp4j.FoldingRangeKind.Comment;
            case IMPORTS -> org.eclipse.lsp4j.FoldingRangeKind.Imports;
            case REGION -> org.eclipse.lsp4j.FoldingRangeKind.Region;
        });
        return converted;
    }

    static org.eclipse.lsp4j.InlayHint inlayHint(org.groovyscope.engine.model.InlayHint hint) {
        org.eclipse.lsp4j.InlayHint converted = new org.eclipse.lsp4j.InlayHint(toLsp(hint.position()),
                Either.forLeft(hint.label()));
        converted.setKind(org.eclipse.lsp4j.InlayHintKind.Type);
        return converted;
    }

    static org.groovyscope.engine.model.Range fromLsp(Range range) {
        return new org.groovyscope.engine.model.Range(fromLsp(range.getStart()), fromLsp(range.getEnd()));
    }

    static DiagnosticSeverity severity(org.groovyscope.engine.model.Diagnostic.Severity severity) {
        return switch (severity) {
            case ERROR -> DiagnosticSeverity.Error;
            case WARNING -> DiagnosticSeverity.Warning;
            case INFORMATION -> DiagnosticSeverity.Information;
            case HINT -> DiagnosticSeverity.Hint;
        };
    }

    static SymbolKind symbolKind(org.groovyscope.engine.model.SymbolKind kind) {
        if (kind == null) {
            return SymbolKind.Variable;
        }
        return switch (kind) {
            case PACKAGE -> SymbolKind.Package;
            case CLASS -> SymbolKind.Class;
            case INTERFACE, TRAIT, ANNOTATION -> SymbolKind.Interface;
            case ENUM -> SymbolKind.Enum;
            case ENUM_CONSTANT -> SymbolKind.EnumMember;
            case RECORD -> SymbolKind.Struct;
            case SCRIPT -> SymbolKind.File;
            case CONSTRUCTOR -> SymbolKind.Constructor;
            case METHOD -> SymbolKind.Method;
            case FIELD -> SymbolKind.Field;
            case PROPERTY -> SymbolKind.Property;
            case PARAMETER, VARIABLE -> SymbolKind.Variable;
            case IMPORT -> SymbolKind.Module;
        };
    }

    static CompletionItemKind completionKind(org.groovyscope.engine.model.SymbolKind kind) {
        if (kind == null) {
            return CompletionItemKind.Keyword;
        }
        return switch (kind) {
            case PACKAGE, IMPORT -> CompletionItemKind.Module;
            case CLASS, RECORD, SCRIPT -> CompletionItemKind.Class;
            case INTERFACE, TRAIT, ANNOTATION -> CompletionItemKind.Interface;
            case ENUM -> CompletionItemKind.Enum;
            case ENUM_CONSTANT -> CompletionItemKind.EnumMember;
            case CONSTRUCTOR -> CompletionItemKind.Constructor;
            case METHOD -> CompletionItemKind.Method;
            case FIELD -> CompletionItemKind.Field;
            case PROPERTY -> CompletionItemKind.Property;
            case PARAMETER, VARIABLE -> CompletionItemKind.Variable;
        };
    }

    /**
     * The range starts on the line of the name, not on the annotations above it: clients that print a symbol's
     * position (Kiro's {@code code} tool prints the range start) then point at the declaration itself, so a
     * follow-up request at that line lands on the name instead of on {@code @SuppressWarnings}.
     */
    static DocumentSymbol documentSymbol(Symbol symbol) {
        org.groovyscope.engine.model.Range whole = symbol.location().range();
        boolean selectionInside = symbol.selectionRange() != null && whole.contains(symbol.selectionRange());
        if (selectionInside && symbol.selectionRange().start().line() > whole.start().line()) {
            whole = new org.groovyscope.engine.model.Range(
                    new org.groovyscope.engine.model.Position(symbol.selectionRange().start().line(), 0), whole.end());
        }
        Range range = toLsp(whole);
        Range selection = selectionInside ? toLsp(symbol.selectionRange()) : range;
        List<DocumentSymbol> children = new ArrayList<>();
        for (Symbol child : symbol.children()) {
            if (child.location() != null) {
                children.add(documentSymbol(child));
            }
        }
        return new DocumentSymbol(symbol.name(), symbolKind(symbol.kind()), range, selection, symbol.detail(), children);
    }

    static WorkspaceSymbol workspaceSymbol(Symbol symbol) {
        org.groovyscope.engine.model.Range range = symbol.selectionRange() != null
                ? symbol.selectionRange()
                : symbol.location().range();
        Location location = new Location(symbol.location().uri(), toLsp(range));
        return new WorkspaceSymbol(symbol.name(), symbolKind(symbol.kind()), Either.forLeft(location), symbol.container());
    }

    /** A call hierarchy item carrying enough data to find the declaration again. */
    static CallHierarchyItem callHierarchyItem(Symbol symbol) {
        Range range = toLsp(symbol.location().range());
        Range selection = symbol.selectionRange() != null ? toLsp(symbol.selectionRange()) : range;
        CallHierarchyItem item = new CallHierarchyItem(symbol.name(), symbolKind(symbol.kind()), symbol.location().uri(),
                range, selection);
        item.setDetail(symbol.detail());
        return item;
    }

    /** The symbol a call hierarchy item was made from (name, kind, uri and range are what matter). */
    static Symbol symbolOf(CallHierarchyItem item) {
        org.groovyscope.engine.model.Range range = new org.groovyscope.engine.model.Range(
                fromLsp(item.getRange().getStart()), fromLsp(item.getRange().getEnd()));
        return new Symbol(item.getName(), org.groovyscope.engine.model.SymbolKind.METHOD, null,
                new org.groovyscope.engine.model.Location(item.getUri(), range), null, item.getDetail(), List.of());
    }

    static String key(Symbol symbol) {
        return symbol.location() == null
                ? "external:" + symbol.container() + "#" + symbol.name() + "#" + symbol.detail()
                : symbol.location().uri() + "#" + symbol.location().range();
    }

    static CompletionItem completionItem(Completion completion) {
        CompletionItem item = new CompletionItem(completion.label());
        item.setKind(completionKind(completion.kind()));
        item.setDetail(completion.detail());
        item.setInsertText(completion.insertText());
        return item;
    }

    static org.eclipse.lsp4j.Hover hover(Hover hover) {
        MarkupContent content = new MarkupContent(MarkupKind.MARKDOWN, hover.markdown());
        return hover.range() == null ? new org.eclipse.lsp4j.Hover(content)
                : new org.eclipse.lsp4j.Hover(content, toLsp(hover.range()));
    }

    static org.eclipse.lsp4j.SignatureHelp signatureHelp(SignatureHelp help) {
        List<SignatureInformation> signatures = new ArrayList<>();
        for (SignatureHelp.Signature signature : help.signatures()) {
            List<ParameterInformation> parameters = new ArrayList<>();
            for (String parameter : signature.parameters()) {
                parameters.add(new ParameterInformation(parameter));
            }
            SignatureInformation information = new SignatureInformation(signature.label());
            information.setParameters(parameters);
            if (signature.documentation() != null) {
                information.setDocumentation(signature.documentation());
            }
            signatures.add(information);
        }
        return new org.eclipse.lsp4j.SignatureHelp(signatures, help.activeSignature(), help.activeParameter());
    }
}
