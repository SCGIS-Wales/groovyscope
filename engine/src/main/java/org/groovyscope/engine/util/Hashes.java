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
package org.groovyscope.engine.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256, the one digest the engine uses for content keys, workspace keys and fingerprints. */
public final class Hashes {

    private Hashes() {
    }

    /** The SHA-256 digest of {@code text} in UTF-8. */
    public static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The SHA-256 of {@code text} as lower-case hex. */
    public static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text));
    }

    /** The first {@code bytes} bytes of the SHA-256 of {@code text}, as lower-case hex. */
    public static String sha256Hex(String text, int bytes) {
        return HexFormat.of().formatHex(sha256(text), 0, bytes);
    }
}
