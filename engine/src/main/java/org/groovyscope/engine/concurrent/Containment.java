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

/**
 * Which failures an analyser contains (reports and survives) instead of letting them end the process: compiler
 * bugs ({@code GroovyBugError} is an {@code AssertionError}), classes that fail to link, runtime exceptions and a
 * stack overflow on deeply nested code. {@code OutOfMemoryError} and other virtual machine errors are not: with
 * {@code -XX:+ExitOnOutOfMemoryError} the process exits and the front end restarts it.
 */
public final class Containment {

    private Containment() {
    }

    public static boolean recoverable(Throwable failure) {
        return failure instanceof RuntimeException || failure instanceof LinkageError
                || failure instanceof AssertionError || failure instanceof StackOverflowError;
    }
}
