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
package org.groovyscope.engine.model;

/**
 * How sure the engine is that a reference points at a declaration. Dynamic Groovy dispatch means some
 * call sites can only be matched by name; those are never silently promoted.
 */
public enum Confidence {
    /** The compiler or the declared type pins the target exactly. */
    STATIC,
    /** A single candidate matched on the receiver's known type and arity. */
    INFERRED,
    /** Matched by name only; the receiver type is dynamic or unknown. */
    DYNAMIC_UNKNOWN
}
