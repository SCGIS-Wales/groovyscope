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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.groovyscope.engine.concurrent.MemoryGuard;
import org.groovyscope.engine.log.EngineLog;
import org.groovyscope.engine.model.Position;
import org.groovyscope.engine.services.LanguageServices;
import org.groovyscope.engine.workspace.WorkspaceModel;

/**
 * Reading the parameters of the {@code groovyscope/*} requests: plain values, positions, the documents a request
 * targets (directories expanded, anything outside the workspace dropped) and the errors a request answers with.
 */
final class RequestParams {

    private RequestParams() {
    }

    /** A parameter as text, or {@code null} when absent or blank. */
    static String text(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    /** A parameter as a boolean, or {@code null} when absent. */
    static Boolean flag(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value instanceof Boolean bool ? bool : value == null ? null : Boolean.valueOf(value.toString());
    }

    /** A numeric parameter as an int, or {@code null} when absent or not a number. */
    static Integer number(Map<String, Object> params, String key) {
        return params.get(key) instanceof Number number ? number.intValue() : null;
    }

    /** A list parameter as strings, nulls left out; empty when absent. */
    static List<String> stringList(Object value) {
        List<String> list = new ArrayList<>();
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                if (item != null) {
                    list.add(item.toString());
                }
            }
        }
        return list;
    }

    /** The zero-based {@code line} and {@code character} parameters (0 when absent). */
    static Position positionOf(Map<String, Object> params) {
        int line = params.get("line") instanceof Number number ? number.intValue() : 0;
        int character = params.get("character") instanceof Number number ? number.intValue() : 0;
        return new Position(line, character);
    }

    /** The {@code uri} parameter as text, or null. */
    static String uriOf(Map<String, Object> params) {
        Object uri = params == null ? null : params.get("uri");
        return uri == null ? null : uri.toString();
    }

    /** An invalid-params error with {@code message}. */
    static ResponseErrorException invalid(String message) {
        return new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidParams, message, null));
    }

    /** {@code {uri: [[start, end], ...]}} (1-based, inclusive) from a request, or null when absent. */
    static Map<String, List<int[]>> lineRanges(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, List<int[]>> ranges = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            List<int[]> list = new ArrayList<>();
            if (entry.getValue() instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof List<?> pair && pair.size() == 2 && pair.get(0) instanceof Number start
                            && pair.get(1) instanceof Number end) {
                        list.add(new int[] {start.intValue(), end.intValue()});
                    }
                }
            }
            ranges.put(String.valueOf(entry.getKey()), list);
        }
        return ranges;
    }

    /** Refuses a whole-workspace request while the heap is under pressure; cheap requests are unaffected. */
    static void requireHeadroom() {
        MemoryGuard.installed().filter(MemoryGuard::underPressure).ifPresent(guard -> {
            throw new ResponseErrorException(new ResponseError(ResponseErrorCode.RequestFailed, guard.advice(), null));
        });
    }

    /**
     * The {@code uris} parameter with every directory replaced by the workspace sources under it. URIs whose real
     * path lies outside the workspace root are dropped: requests never read files the workspace does not hold.
     */
    static List<String> targets(LanguageServices current, Map<String, Object> params) {
        List<String> inside = new ArrayList<>();
        for (String uri : stringList(params.get("uris"))) {
            if (current.workspace().contains(uri)) {
                inside.add(uri);
            } else {
                EngineLog.warn("%s", "ignoring " + uri + ": outside the workspace root");
            }
        }
        return expandDirectories(inside, current.workspace().sourceUris());
    }

    /** True when the request named documents but none of them is inside the workspace (so nothing is checked). */
    static boolean noneInside(Map<String, Object> params, List<String> targets) {
        return targets.isEmpty() && !stringList(params.get("uris")).isEmpty();
    }

    /** True when a {@code uri} parameter does not name a file inside the workspace root. */
    static boolean outside(LanguageServices current, Object uri) {
        return uri == null || !current.workspace().contains(uri.toString());
    }

    /**
     * Directories expand to the known documents under them; a directory with none stays as it is, so the request
     * matches nothing instead of falling back to the whole workspace.
     */
    static List<String> expandDirectories(List<String> uris, Collection<String> known) {
        List<String> expanded = new ArrayList<>();
        for (String uri : uris) {
            Path path = WorkspaceModel.toPath(uri);
            if (path == null || !java.nio.file.Files.isDirectory(path)) {
                expanded.add(uri);
                continue;
            }
            String prefix = WorkspaceModel.toUri(path);
            String under = prefix.endsWith("/") ? prefix : prefix + "/";
            List<String> inside = known.stream().filter(k -> k.startsWith(under)).sorted().toList();
            expanded.addAll(inside.isEmpty() ? List.of(uri) : inside);
        }
        return expanded;
    }
}
