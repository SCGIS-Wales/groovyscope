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
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
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
    /** The documents this connection has open, so they can be released when it ends. */
    private final java.util.Set<String> opened = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final RequestExecutors requests;

    GroovyscopeTextDocumentService(Supplier<LanguageServices> services, DiagnosticsPublisher diagnostics,
            RequestExecutors requests) {
        this.services = services;
        this.diagnostics = diagnostics;
        this.requests = requests;
    }

    /** The documents this connection currently has open. */
    java.util.Set<String> openedDocuments() {
        return java.util.Set.copyOf(opened);
    }

    /**
     * Closes every document this connection opened: an editor that crashed or disconnected without
     * {@code didClose} must not leave its unsaved text overriding the disk for other clients.
     */
    void closeAll() {
        LanguageServices current = services.get();
        java.util.List<String> uris = new java.util.ArrayList<>(opened);
        opened.clear();
        if (current == null || uris.isEmpty()) {
            return;
        }
        for (String uri : uris) {
            current.workspace().close(uri);
        }
        current.markDirty();
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
            String uri = params.getTextDocument().getUri();
            if (opened.add(uri)) {
                current.workspace().open(uri, params.getTextDocument().getText());
            } else {
                // Opened again by the same connection: one reference, new text.
                current.workspace().change(uri, params.getTextDocument().getText());
            }
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
            if (opened.add(uri)) {
                // A change without didOpen from this connection still makes the overlay this connection's to release.
                current.workspace().open(uri, text);
            } else {
                current.workspace().change(uri, text);
            }
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
            String uri = params.getTextDocument().getUri();
            if (opened.remove(uri)) {
                current.workspace().close(uri);
            }
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
        return requests.lightAbout(params.getTextDocument().getUri(), cancel -> {
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
        return requests.lightAbout(params.getTextDocument().getUri(), cancel -> {
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
        return requests.heavy(cancel -> {
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
        return requests.lightAbout(params.getTextDocument().getUri(), cancel -> {
            return fullHover(ready(), params.getTextDocument().getUri(), Converters.fromLsp(params.getPosition()))
                    .map(Converters::hover).orElse(null);
        });
    }

    /** Hover text plus runtime-profile parameter details and the notes for findings on the line. */
    static java.util.Optional<org.groovyscope.engine.model.Hover> fullHover(LanguageServices current, String uri,
            org.groovyscope.engine.model.Position position) {
        java.util.Optional<org.groovyscope.engine.model.Hover> hover = current.hover(uri, position);
        List<org.groovyscope.engine.model.Hover.InferredType> assigned = hover
                .map(org.groovyscope.engine.model.Hover::inferredTypes).orElse(List.of());
        java.util.Optional<String> parameter = current.quality().parameterHover(uri, position);
        if (parameter.isPresent()) {
            hover = java.util.Optional.of(new org.groovyscope.engine.model.Hover(parameter.get(),
                    hover.map(org.groovyscope.engine.model.Hover::range).orElse(null), assigned));
        }
        String notes = findingNotes(current, uri, position.line());
        if (notes.isEmpty()) {
            return hover;
        }
        String markdown = hover.map(h -> h.markdown() + "\n\n---\n\n").orElse("") + notes;
        org.groovyscope.engine.model.Range range = hover.map(org.groovyscope.engine.model.Hover::range).orElse(null);
        return java.util.Optional.of(new org.groovyscope.engine.model.Hover(markdown, range, assigned));
    }

    /**
     * Why and how to fix, for each practice or lint finding on a line (deduplicated by code). Hover runs on the light
     * lane, so only findings already computed (by the diagnostics pass) are read: nothing is linted here.
     */
    static String findingNotes(LanguageServices services, String uri, int line) {
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        for (org.groovyscope.engine.practices.PracticeFinder.Practice practice : services.quality().cachedPractices(uri)) {
            if (practice.diagnostic().range().start().line() == line) {
                codes.add(practice.diagnostic().code());
            }
        }
        String text = services.workspace().text(uri);
        if (text != null) {
            for (org.groovyscope.engine.model.Diagnostic diagnostic : services.quality().cachedLint(uri, text)) {
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
        return requests.lightAbout(params.getTextDocument().getUri(), cancel -> {
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
        return requests.lightAbout(params.getTextDocument().getUri(), cancel -> {
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
        return requests.lightAbout(params.getTextDocument().getUri(), cancel -> ready()
                .signatureHelp(params.getTextDocument().getUri(), Converters.fromLsp(params.getPosition()))
                .map(Converters::signatureHelp)
                .orElse(null));
    }

    @Override
    public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(
            PrepareRenameParams params) {
        return requests.light(cancel -> {
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
        return requests.heavy(cancel -> {
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

    /** Code action kind of the "apply every safe fix" source action. */
    static final String FIX_ALL_KIND = CodeActionKind.SourceFixAll + ".groovyscope";

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return requests.heavy(cancel -> {
            String uri = params.getTextDocument().getUri();
            LanguageServices current = ready();
            List<String> only = params.getContext() == null ? null : params.getContext().getOnly();
            List<Either<Command, CodeAction>> actions = new ArrayList<>();
            if (wanted(only, CodeActionKind.QuickFix, false)) {
                for (org.groovyscope.engine.model.CodeAction fix : current.quality().codeActions(uri,
                        Converters.fromLsp(params.getRange()))) {
                    CodeAction action = action(fix.title(), CodeActionKind.QuickFix, uri, fix.edits());
                    action.setDiagnostics(List.of(Converters.toLsp(fix.diagnostic())));
                    actions.add(Either.forRight(action));
                }
            }
            // Source actions are computed only when asked for: fixing a whole document can run the type checker.
            if (wanted(only, FIX_ALL_KIND, true)) {
                List<org.groovyscope.engine.model.TextEdit> edits = current.quality().fixAll(uri);
                if (!edits.isEmpty()) {
                    actions.add(Either.forRight(action("Fix all safe groovyscope findings", FIX_ALL_KIND, uri, edits)));
                }
            }
            if (wanted(only, CodeActionKind.SourceOrganizeImports, true)) {
                List<org.groovyscope.engine.model.TextEdit> edits = current.quality().organizeImports(uri);
                if (!edits.isEmpty()) {
                    actions.add(Either.forRight(action("Remove unused imports", CodeActionKind.SourceOrganizeImports,
                            uri, edits)));
                }
            }
            return actions;
        });
    }

    /**
     * Whether a client's {@code only} filter asks for actions of {@code kind}: a requested kind matches itself and
     * every kind below it ({@code source} matches {@code source.organizeImports}). With no filter, quick fixes are
     * offered and source actions are not.
     */
    static boolean wanted(List<String> only, String kind, boolean sourceAction) {
        if (only == null || only.isEmpty()) {
            return !sourceAction;
        }
        for (String requested : only) {
            if (kind.equals(requested) || kind.startsWith(requested + ".")) {
                return true;
            }
        }
        return false;
    }

    private static CodeAction action(String title, String kind, String uri,
            List<org.groovyscope.engine.model.TextEdit> edits) {
        CodeAction action = new CodeAction(title);
        action.setKind(kind);
        List<TextEdit> converted = new ArrayList<>();
        for (org.groovyscope.engine.model.TextEdit edit : edits) {
            converted.add(Converters.toLsp(edit));
        }
        Map<String, List<TextEdit>> changes = new HashMap<>();
        changes.put(uri, converted);
        action.setEdit(new WorkspaceEdit(changes));
        return action;
    }

    @Override
    public CompletableFuture<DocumentDiagnosticReport> diagnostic(DocumentDiagnosticParams params) {
        return requests.heavy(cancel -> {
            String uri = params.getTextDocument().getUri();
            LanguageServices current = ready();
            List<org.eclipse.lsp4j.Diagnostic> items = new ArrayList<>();
            for (Diagnostic diagnostic : current.quality().publishedDiagnostics(List.of(uri)).getOrDefault(uri, List.of())) {
                items.add(Converters.toLsp(diagnostic, current.quality().fixable(diagnostic)));
            }
            return new DocumentDiagnosticReport(new RelatedFullDocumentDiagnosticReport(items));
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
            ImplementationParams params) {
        return requests.lightAbout(params.getTextDocument().getUri(), cancel -> {
            List<Location> locations = new ArrayList<>();
            for (org.groovyscope.engine.model.Location location : ready().implementations(
                    params.getTextDocument().getUri(), Converters.fromLsp(params.getPosition()))) {
                locations.add(Converters.toLsp(location));
            }
            return Either.forLeft(locations);
        });
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {
        return requests.lightAbout(params.getTextDocument().getUri(), cancel -> {
            List<DocumentHighlight> highlights = new ArrayList<>();
            for (org.groovyscope.engine.model.Highlight highlight : ready().highlights(
                    params.getTextDocument().getUri(), Converters.fromLsp(params.getPosition()))) {
                highlights.add(Converters.highlight(highlight));
            }
            return highlights;
        });
    }

    @Override
    public CompletableFuture<List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) {
        return requests.lightAbout(params.getTextDocument().getUri(), cancel -> {
            List<FoldingRange> ranges = new ArrayList<>();
            for (org.groovyscope.engine.model.FoldingRange range : ready().foldingRanges(params.getTextDocument().getUri())) {
                ranges.add(Converters.foldingRange(range));
            }
            return ranges;
        });
    }

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
        return requests.lightAbout(params.getTextDocument().getUri(), cancel -> {
            List<InlayHint> hints = new ArrayList<>();
            for (org.groovyscope.engine.model.InlayHint hint : ready().inlayHints(params.getTextDocument().getUri(),
                    Converters.fromLsp(params.getRange()))) {
                hints.add(Converters.inlayHint(hint));
            }
            return hints;
        });
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
        return requests.heavy(cancel -> {
            List<TextEdit> edits = new ArrayList<>();
            try {
                for (org.groovyscope.engine.model.TextEdit edit : ready().quality().formatRange(
                        params.getTextDocument().getUri(), Converters.fromLsp(params.getRange()))) {
                    edits.add(Converters.toLsp(edit));
                }
            } catch (IllegalStateException e) {
                throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidRequest, e.getMessage(), null));
            }
            return edits;
        });
    }

    @Override
    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
        return requests.lightAbout(params.getTextDocument().getUri(), cancel -> ready()
                .callHierarchyItem(params.getTextDocument().getUri(), Converters.fromLsp(params.getPosition()))
                .map(symbol -> List.of(Converters.callHierarchyItem(symbol)))
                .orElse(List.of()));
    }

    @Override
    public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams params) {
        return requests.lightAbout(params.getItem().getUri(), cancel -> {
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
        return requests.lightAbout(params.getItem().getUri(), cancel -> {
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
        return requests.lightAbout(params.getTextDocument().getUri(), cancel ->
                new SemanticTokens(ready().semanticTokens(params.getTextDocument().getUri())));
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return requests.heavy(cancel -> {
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
