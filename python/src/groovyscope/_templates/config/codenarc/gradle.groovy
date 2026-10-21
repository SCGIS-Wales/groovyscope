ruleset {
    description 'groovyscope profile for Gradle build scripts (*.gradle): the Gradle DSL is dynamic by design'

    ruleset('rulesets/basic.xml')
    ruleset('rulesets/braces.xml')
    ruleset('rulesets/convention.xml') {
        // a build script is a dynamic DSL: `def` and untyped closures are its idiom, and it is never compiled static
        exclude 'CompileStatic'
        exclude 'NoDef'
        exclude 'VariableTypeRequired'
        exclude 'MethodReturnTypeRequired'
        exclude 'MethodParameterTypeRequired'
        exclude 'FieldTypeRequired'
        exclude 'TrailingComma'
        exclude 'ImplicitClosureParameter'
        exclude 'IfStatementCouldBeTernary'
        exclude 'CouldBeSwitchStatement'
        exclude 'ImplicitReturnStatement'
        exclude 'StaticMethodsBeforeInstanceMethods'
        exclude 'PublicMethodsBeforeNonPublicMethods'
    }
    ruleset('rulesets/exceptions.xml')
    ruleset('rulesets/formatting.xml') {
        'LineLength' { length = 120 }
        exclude 'ClassStartsWithBlankLine'
        exclude 'ClassEndsWithBlankLine'
        exclude 'SpaceAroundMapEntryColon'
    }
    ruleset('rulesets/groovyism.xml')
    ruleset('rulesets/imports.xml') {
        // unused and redundant imports are dead code: warnings, so the editor and the after-write hook show them
        'UnusedImport' { priority = 2 }
        'UnnecessaryGroovyImport' { priority = 2 }
        'DuplicateImport' { priority = 2 }
        'ImportFromSamePackage' { priority = 2 }
    }
    ruleset('rulesets/security.xml') {
        exclude 'JavaIoPackageAccess'   // build logic reads and writes files
    }
    ruleset('rulesets/generic.xml') {
        include 'IllegalClassReference'
        'IllegalClassReference' { priority = 1; classNames = 'groovy.lang.Grab, groovy.lang.Grapes, groovy.util.Eval' }
    }
    ruleset('rulesets/unnecessary.xml') {
        exclude 'UnnecessaryReturnKeyword'
        exclude 'UnnecessaryGetter'
    }
    ruleset('rulesets/unused.xml')
}
