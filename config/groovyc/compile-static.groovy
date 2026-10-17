// Compiler configuration script: groovyc --configscript config/groovyc/compile-static.groovy
// or, in Gradle:
//   tasks.withType(GroovyCompile).configureEach { groovyOptions.configurationScript = file('config/groovyc/compile-static.groovy') }
// Makes @CompileStatic the default; opt out per class with @CompileDynamic and a justification comment.
withConfig(configuration) {
    ast(groovy.transform.CompileStatic)
}

// Groovy 6 only (add compileOnly 'org.apache.groovy:groovy-typecheckers:6.0.0'): replace the block above with
//
// withConfig(configuration) {
//     ast(groovy.transform.CompileStatic, extensions: [
//         'groovy.typecheckers.NullChecker(strict: true)',
//         'groovy.typecheckers.SqlInjectionChecker'
//     ])
// }
