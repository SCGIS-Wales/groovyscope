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
package org.groovyscope.engine.compile;

import java.io.IOException;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * The parent of every workspace class loader. Workspace code may see the JDK and the Groovy runtime the
 * engine ships, and nothing else of the engine's own classpath: not LSP4J, not Gson, not the test
 * classpath. Global AST transformations are discovered through resources, so those are filtered the same
 * way, which keeps third-party transformations on the engine's classpath from ever touching workspace code.
 */
public final class WorkspaceParentClassLoader extends ClassLoader {

    private static final List<String> CLASS_PREFIXES = List.of(
            "groovy.", "groovyjarjar", "org.codehaus.groovy.", "org.apache.groovy.");
    private static final List<String> RESOURCE_PREFIXES = List.of(
            "groovy/", "groovyjarjar", "org/codehaus/groovy/", "org/apache/groovy/", "META-INF/groovy/",
            "META-INF/services/org.codehaus.groovy.", "META-INF/services/org.apache.groovy.",
            "META-INF/services/groovy.");

    private final ClassLoader engineLoader;

    public WorkspaceParentClassLoader(ClassLoader engineLoader) {
        super(ClassLoader.getPlatformClassLoader());
        this.engineLoader = engineLoader;
    }

    static boolean isGroovyClass(String name) {
        for (String prefix : CLASS_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static boolean isGroovyResource(String name) {
        for (String prefix : RESOURCE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (isGroovyClass(name)) {
            return engineLoader.loadClass(name);
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    protected URL findResource(String name) {
        return isGroovyResource(name) ? engineLoader.getResource(name) : null;
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        if (!isGroovyResource(name)) {
            return Collections.emptyEnumeration();
        }
        Enumeration<URL> found = engineLoader.getResources(name);
        if (!TRANSFORM_SERVICE.equals(name)) {
            return found;
        }
        // Global transformations may only come from the Groovy runtime itself, never from a neighbouring jar.
        List<URL> groovyOnly = new ArrayList<>();
        String groovyHome = groovyRuntimeLocation();
        while (found.hasMoreElements()) {
            URL url = found.nextElement();
            if (groovyHome == null || url.toString().contains(groovyHome)) {
                groovyOnly.add(url);
            }
        }
        return Collections.enumeration(groovyOnly);
    }

    static final String TRANSFORM_SERVICE = "META-INF/services/org.codehaus.groovy.transform.ASTTransformation";

    private static String groovyRuntimeLocation() {
        try {
            CodeSource source = groovy.lang.GroovyObject.class.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null ? null : source.getLocation().toString();
        } catch (SecurityException e) {
            return null;
        }
    }
}
