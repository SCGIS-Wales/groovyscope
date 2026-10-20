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

import groovy.lang.GroovyClassLoader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.groovyscope.engine.util.Closeables;

/**
 * What every workspace compile sets up the same way: a tolerant UTF-8 configuration, a resolution loader over the
 * workspace classpath (below the {@link WorkspaceParentClassLoader}), and the loader AST transformations come from,
 * which is the resolution loader only in a trusted workspace.
 */
public final class CompilationScaffold {

    private CompilationScaffold() {
    }

    /** A configuration that reads UTF-8 and keeps going after any number of errors. */
    public static CompilerConfiguration configuration() {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setSourceEncoding("UTF-8");
        configuration.setTolerance(Integer.MAX_VALUE);
        return configuration;
    }

    /** A loader over {@code classpath} that resolves the workspace's types; closed again if an entry is malformed. */
    public static GroovyClassLoader resolutionLoader(CompilerConfiguration configuration, List<Path> classpath) {
        ClassLoader parent = new WorkspaceParentClassLoader(GroovyCompiler.class.getClassLoader());
        GroovyClassLoader loader = new GroovyClassLoader(parent, configuration);
        try {
            for (Path entry : classpath) {
                loader.addURL(toUrl(entry));
            }
        } catch (IllegalArgumentException e) {
            Closeables.closeQuietly(loader);
            throw e;
        }
        return loader;
    }

    /**
     * The loader AST transformations load from: the resolution loader itself when the workspace is trusted, else a
     * loader with the same parent and none of the workspace classpath, so its transformations never run.
     */
    public static GroovyClassLoader transformLoader(GroovyClassLoader resolution, CompilerConfiguration configuration,
            boolean trust) {
        return trust ? resolution : new GroovyClassLoader(resolution.getParent(), configuration);
    }

    /** A classpath entry as a URL. */
    public static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("bad classpath entry " + path, e);
        }
    }
}
