// Compiler configuration script: groovyc --configscript config/groovyc/compile-static.groovy
// or GroovyCompile.groovyOptions.configurationScript in Gradle. Makes @CompileStatic the default;
// opt out per class with @CompileDynamic and a justification comment.
withConfig(configuration) {
    ast(groovy.transform.CompileStatic)
}
