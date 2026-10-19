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
package org.groovyscope.engine.concurrent;

import java.util.function.Supplier;

/**
 * Whether the request on this thread may be answered from the snapshot already published while a newer compile is
 * pending. Light requests (hover, outline, definition) mark the one document they are about: when that document is
 * unchanged since the published compile, they answer from it at once and the compile runs in the background (stale
 * while revalidating); a request about a changed document still waits for the fresh compile.
 */
public final class Freshness {

    private static final ThreadLocal<String> STALE_OK = new ThreadLocal<>();

    private Freshness() {
    }

    /** Runs {@code work} allowing the published snapshot for requests about {@code uri}. */
    public static <T> T staleOkFor(String uri, Supplier<T> work) {
        String previous = STALE_OK.get();
        STALE_OK.set(uri);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                STALE_OK.remove();
            } else {
                STALE_OK.set(previous);
            }
        }
    }

    /** The document the current request is about when it may be answered from the published snapshot; else null. */
    public static String staleOkUri() {
        return STALE_OK.get();
    }
}
