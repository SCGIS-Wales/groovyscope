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
        }
        return converted;
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

    static DocumentSymbol documentSymbol(Symbol symbol) {
        Range range = toLsp(symbol.location().range());
        Range selection = symbol.selectionRange() != null && symbol.location().range().contains(symbol.selectionRange())
                ? toLsp(symbol.selectionRange())
                : range;
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

    static CompletionItem completionItem(Completion completion) {
        CompletionItem item = new CompletionItem(completion.label());
        item.setKind(completionKind(completion.kind()));
        item.setDetail(completion.detail());
        item.setInsertText(completion.insertText());
        return item;
    }

    static org.eclipse.lsp4j.Hover hover(Hover hover) {
        return new org.eclipse.lsp4j.Hover(new MarkupContent(MarkupKind.MARKDOWN, hover.markdown()), toLsp(hover.range()));
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
