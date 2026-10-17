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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentDiagnosticParams;
import org.eclipse.lsp4j.DocumentDiagnosticReport;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.groovyscope.engine.model.CallSite;
import org.groovyscope.engine.model.Completion;
import org.groovyscope.engine.model.Definition;
import org.groovyscope.engine.model.Diagnostic;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.model.Reference;
import org.groovyscope.engine.model.RenamePreparation;
import org.groovyscope.engine.model.RenameResult;
import org.groovyscope.engine.model.Symbol;
import org.groovyscope.engine.services.LanguageServices;

/** Text document requests, each answered off the JSON-RPC thread from the current snapshot. */
final class GroovyscopeTextDocumentService implements TextDocumentService {

    private final Supplier<LanguageServices> services;
    private final DiagnosticsPublisher diagnostics;

    GroovyscopeTextDocumentService(Supplier<LanguageServices> services, DiagnosticsPublisher diagnostics) {
        this.services = services;
        this.diagnostics = diagnostics;
    }

    private LanguageServices ready() {
        LanguageServices current = services.get();
        if (current == null) {
            throw new ResponseErrorException(new ResponseError(ResponseErrorCode.ServerNotInitialized,
                    "groovyscope engine has not been initialized", null));
        }
        return current;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        LanguageServices current = services.get();
        if (current != null) {
            current.workspace().open(params.getTextDocument().getUri(), params.getTextDocument().getText());
            current.markDirty();
            diagnostics.schedule();
        }
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        LanguageServices current = services.get();
        if (current == null) {
            return;
        }
        String uri = params.getTextDocument().getUri();
        String text = current.workspace().openText(uri);
        for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
            if (change.getRange() == null) {
                text = change.getText();
            } else if (text != null) {
                text = applyIncremental(text, change);
            }
        }
        if (text != null) {
            current.workspace().change(uri, text);
            current.markDirty();
            diagnostics.schedule();
        }
    }

    static String applyIncremental(String text, TextDocumentContentChangeEvent change) {
        org.groovyscope.engine.index.TextLines lines = new org.groovyscope.engine.index.TextLines(text);
        int start = lines.offsetOf(Converters.fromLsp(change.getRange().getStart()));
        int end = lines.offsetOf(Converters.fromLsp(change.getRange().getEnd()));
        return text.substring(0, start) + change.getText() + text.substring(Math.max(start, end));
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        LanguageServices current = services.get();
        if (current != null) {
            current.workspace().close(params.getTextDocument().getUri());
            current.markDirty();
            diagnostics.schedule();
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        LanguageServices current = services.get();
        if (current != null) {
            current.markDirty();
            diagnostics.schedule();
        }
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = ready();
            String uri = params.getTextDocument().getUri();
            org.groovyscope.engine.model.Position position = Converters.fromLsp(params.getPosition());
            List<org.groovyscope.engine.model.Location> declarations = current.quality().parameterDeclarations(uri, position);
            if (!declarations.isEmpty()) {
                List<Location> locations = new ArrayList<>();
                declarations.forEach(location -> locations.add(Converters.toLsp(location)));
                return Either.forLeft(locations);
            }
            Optional<Definition> definition = current.definition(uri, position);
            return Either.forLeft(locations(definition));
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
            TypeDefinitionParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            Optional<Definition> definition = ready().typeDefinition(params.getTextDocument().getUri(),
                    Converters.fromLsp(params.getPosition()));
            return Either.forLeft(locations(definition));
        });
    }

    private static List<Location> locations(Optional<Definition> definition) {
        List<Location> locations = new ArrayList<>();
        if (definition.isPresent() && definition.get().isInWorkspace()) {
            Symbol symbol = definition.get().symbol();
            org.groovyscope.engine.model.Range range = symbol.selectionRange() != null
                    ? symbol.selectionRange()
                    : symbol.location().range();
            locations.add(new Location(symbol.location().uri(), Converters.toLsp(range)));
        }
        return locations;
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            boolean includeDeclaration = params.getContext() != null && params.getContext().isIncludeDeclaration();
            LanguageServices current = ready();
            org.groovyscope.engine.model.Position position = Converters.fromLsp(params.getPosition());
            List<org.groovyscope.engine.model.Location> parameterReferences = current.quality()
                    .parameterReferences(params.getTextDocument().getUri(), position, includeDeclaration);
            if (!parameterReferences.isEmpty()) {
                List<Location> locations = new ArrayList<>();
                parameterReferences.forEach(location -> locations.add(Converters.toLsp(location)));
                return locations;
            }
            List<Reference> references = current.references(params.getTextDocument().getUri(), position,
                    includeDeclaration);
            List<Location> locations = new ArrayList<>();
            for (Reference reference : references) {
                locations.add(Converters.toLsp(reference.location()));
            }
            return locations;
        });
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            LanguageServices current = ready();
            String uri = params.getTextDocument().getUri();
            org.groovyscope.engine.model.Position position = Converters.fromLsp(params.getPosition());
            java.util.Optional<org.groovyscope.engine.model.Hover> hover = current.hover(uri, position);
            java.util.Optional<String> parameter = current.quality().parameterHover(uri, position);
            if (parameter.isPresent()) {
                hover = java.util.Optional.of(new org.groovyscope.engine.model.Hover(parameter.get(),
                        hover.map(org.groovyscope.engine.model.Hover::range).orElse(null)));
            }
            String notes = findingNotes(current, uri, position.line());
            if (notes.isEmpty()) {
                return hover.map(Converters::hover).orElse(null);
            }
            String markdown = hover.map(h -> h.markdown() + "\n\n---\n\n").orElse("") + notes;
            org.groovyscope.engine.model.Range range = hover.map(org.groovyscope.engine.model.Hover::range).orElse(null);
            return Converters.hover(new org.groovyscope.engine.model.Hover(markdown, range));
        });
    }

    /** Why and how to fix, for each practice or lint finding on a line (deduplicated by code). */
    static String findingNotes(LanguageServices services, String uri, int line) {
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        for (org.groovyscope.engine.practices.PracticeFinder.Practice practice : services.quality().practices(uri)) {
            if (practice.diagnostic().range().start().line() == line) {
                codes.add(practice.diagnostic().code());
            }
        }
        String text = services.workspace().text(uri);
        if (text != null) {
            for (org.groovyscope.engine.model.Diagnostic diagnostic : services.quality().lint(uri, text, null)) {
                if (diagnostic.range().start().line() == line) {
                    codes.add(diagnostic.code());
                }
            }
        }
        List<String> notes = new ArrayList<>();
        for (String code : codes) {
            org.groovyscope.engine.practices.Explanations.hoverNote(code).ifPresent(notes::add);
        }
        return String.join("\n\n", notes);
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            List<Either<SymbolInformation, DocumentSymbol>> symbols = new ArrayList<>();
            for (Symbol symbol : ready().documentSymbols(params.getTextDocument().getUri())) {
                if (symbol.location() != null) {
                    symbols.add(Either.forRight(Converters.documentSymbol(symbol)));
                }
            }
            return symbols;
        });
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            List<CompletionItem> items = new ArrayList<>();
            for (Completion completion : ready().completion(params.getTextDocument().getUri(),
                    Converters.fromLsp(params.getPosition()))) {
                items.add(Converters.completionItem(completion));
            }
            return Either.forRight(new CompletionList(false, items));
        });
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
        return CompletableFutures.computeAsync(cancel -> ready()
                .signatureHelp(params.getTextDocument().getUri(), Converters.fromLsp(params.getPosition()))
                .map(Converters::signatureHelp)
                .orElse(null));
    }

    @Override
    public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(
            PrepareRenameParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            RenamePreparation preparation = ready().prepareRename(params.getTextDocument().getUri(),
                    Converters.fromLsp(params.getPosition()));
            if (preparation.isRefused()) {
                throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidRequest,
                        preparation.refusalReason(), null));
            }
            return Either3.forSecond(new PrepareRenameResult(Converters.toLsp(preparation.range()),
                    preparation.placeholder()));
        });
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            List<TextEdit> edits = new ArrayList<>();
            try {
                for (org.groovyscope.engine.model.TextEdit edit : ready().quality().format(params.getTextDocument().getUri())) {
                    edits.add(Converters.toLsp(edit));
                }
            } catch (IllegalStateException e) {
                throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidRequest, e.getMessage(), null));
            }
            return edits;
        });
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            String uri = params.getTextDocument().getUri();
            List<Either<Command, CodeAction>> actions = new ArrayList<>();
            for (org.groovyscope.engine.model.CodeAction fix : ready().quality().codeActions(uri,
                    new org.groovyscope.engine.model.Range(Converters.fromLsp(params.getRange().getStart()),
                            Converters.fromLsp(params.getRange().getEnd())))) {
                CodeAction action = new CodeAction(fix.title());
                action.setKind(CodeActionKind.QuickFix);
                action.setDiagnostics(List.of(Converters.toLsp(fix.diagnostic())));
                List<TextEdit> edits = new ArrayList<>();
                for (org.groovyscope.engine.model.TextEdit edit : fix.edits()) {
                    edits.add(Converters.toLsp(edit));
                }
                Map<String, List<TextEdit>> changes = new HashMap<>();
                changes.put(uri, edits);
                action.setEdit(new WorkspaceEdit(changes));
                actions.add(Either.forRight(action));
            }
            return actions;
        });
    }

    @Override
    public CompletableFuture<DocumentDiagnosticReport> diagnostic(DocumentDiagnosticParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            String uri = params.getTextDocument().getUri();
            List<org.eclipse.lsp4j.Diagnostic> items = new ArrayList<>();
            for (Diagnostic diagnostic : ready().quality().publishedDiagnostics().getOrDefault(uri, List.of())) {
                items.add(Converters.toLsp(diagnostic));
            }
            return new DocumentDiagnosticReport(new RelatedFullDocumentDiagnosticReport(items));
        });
    }

    @Override
    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
        return CompletableFutures.computeAsync(cancel -> ready()
                .callHierarchyItem(params.getTextDocument().getUri(), Converters.fromLsp(params.getPosition()))
                .map(symbol -> List.of(Converters.callHierarchyItem(symbol)))
                .orElse(List.of()));
    }

    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            Symbol target = Converters.symbolOf(params.getItem());
            Map<String, List<CallSite>> byCaller = new java.util.LinkedHashMap<>();
            for (CallSite site : ready().incomingCalls(target)) {
                byCaller.computeIfAbsent(Converters.key(site.caller()), k -> new ArrayList<>()).add(site);
            }
            List<CallHierarchyIncomingCall> calls = new ArrayList<>();
            for (List<CallSite> sites : byCaller.values()) {
                Symbol caller = sites.get(0).caller();
                if (caller.location() == null) {
                    continue;
                }
                List<Range> ranges = new ArrayList<>();
                for (CallSite site : sites) {
                    ranges.add(Converters.toLsp(site.site().range()));
                }
                calls.add(new CallHierarchyIncomingCall(Converters.callHierarchyItem(caller), ranges));
            }
            return calls;
        });
    }

    @Override
    public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            Symbol container = Converters.symbolOf(params.getItem());
            Map<String, List<CallSite>> byCallee = new java.util.LinkedHashMap<>();
            for (CallSite site : ready().outgoingCalls(container)) {
                byCallee.computeIfAbsent(Converters.key(site.callee()), k -> new ArrayList<>()).add(site);
            }
            List<CallHierarchyOutgoingCall> calls = new ArrayList<>();
            for (List<CallSite> sites : byCallee.values()) {
                Symbol callee = sites.get(0).callee();
                if (callee.location() == null) {
                    continue;
                }
                List<Range> ranges = new ArrayList<>();
                for (CallSite site : sites) {
                    ranges.add(Converters.toLsp(site.site().range()));
                }
                calls.add(new CallHierarchyOutgoingCall(Converters.callHierarchyItem(callee), ranges));
            }
            return calls;
        });
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        return CompletableFutures.computeAsync(cancel ->
                new SemanticTokens(ready().semanticTokens(params.getTextDocument().getUri())));
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return CompletableFutures.computeAsync(cancel -> {
            Position position = Converters.fromLsp(params.getPosition());
            RenameResult result = ready().rename(params.getTextDocument().getUri(), position, params.getNewName());
            if (result.isRefused()) {
                throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidRequest,
                        result.refusalReason(), null));
            }
            Map<String, List<TextEdit>> changes = new HashMap<>();
            for (Map.Entry<String, List<org.groovyscope.engine.model.TextEdit>> entry : result.edits().entrySet()) {
                List<TextEdit> edits = new ArrayList<>();
                for (org.groovyscope.engine.model.TextEdit edit : entry.getValue()) {
                    edits.add(Converters.toLsp(edit));
                }
                changes.put(entry.getKey(), edits);
            }
            return new WorkspaceEdit(changes);
        });
    }
}
