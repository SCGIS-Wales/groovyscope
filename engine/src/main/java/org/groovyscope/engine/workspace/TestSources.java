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
package org.groovyscope.engine.workspace;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What counts as test code, decided in one place: a file in any Gradle source set other than {@code main}
 * ({@code src/test/groovy}, {@code src/it/java}, {@code src/integrationTest/groovy}, {@code src/testFixtures/java}),
 * or a class named like a test ({@code FooTest}, {@code FooTests}, {@code FooSpec}, {@code FooIT}, {@code FooITCase},
 * {@code FooIntegrationTest}). A Jenkins shared library's {@code src/org/...} is production code: a source set
 * needs its language folder.
 */
public final class TestSources {

    private static final Pattern TEST_SOURCE_SET = Pattern.compile(".*/src/(?!main/)[A-Za-z0-9_.-]+/(groovy|java)/.*");
    /** Class name endings of test classes. */
    public static final List<String> TEST_SUFFIXES = List.of("Test", "Tests", "Spec", "IT", "ITCase",
            "IntegrationTest");

    private TestSources() {
    }

    /** Whether a file (URI or path, forward slashes) lies in a test source set. */
    public static boolean isTestPath(String uriOrPath) {
        return uriOrPath != null && TEST_SOURCE_SET.matcher(uriOrPath.replace('\\', '/')).matches();
    }

    /** Whether a simple class name (no package; an inner class's own name) reads like a test class. */
    public static boolean isTestName(String simpleName) {
        return simpleName != null && TEST_SUFFIXES.stream().anyMatch(simpleName::endsWith);
    }

    /** Test code by either sign. */
    public static boolean isTest(String uriOrPath, String simpleName) {
        return isTestPath(uriOrPath) || isTestName(simpleName);
    }
}
