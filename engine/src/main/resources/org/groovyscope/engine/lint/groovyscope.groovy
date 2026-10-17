ruleset {
    description 'groovyscope defensive profile for Groovy 5.x and 6.x (CodeNarc 4.x)'

    ruleset('rulesets/basic.xml')
    ruleset('rulesets/braces.xml')
    ruleset('rulesets/concurrency.xml')
    ruleset('rulesets/convention.xml') {
        // Spock rewrites specifications, so they stay dynamic (docs/practices.md, section 12)
        'CompileStatic' { priority = 2; doNotApplyToFilesMatching = '.*Spec\\.groovy' }
        'NoDef' { priority = 2; doNotApplyToFilesMatching = '.*/(vars|scripts)/.*\\.groovy|.*Spec\\.groovy' }
        exclude 'TrailingComma'
        exclude 'ImplicitClosureParameter'
        exclude 'IfStatementCouldBeTernary'
        exclude 'CouldBeSwitchStatement'
        exclude 'ImplicitReturnStatement'             // contradicts UnnecessaryReturnKeyword; neither is enforced
        exclude 'StaticMethodsBeforeInstanceMethods'
        exclude 'PublicMethodsBeforeNonPublicMethods'
    }
    ruleset('rulesets/design.xml') {
        exclude 'Instanceof'
        exclude 'NestedForLoop'
        exclude 'ImplementationAsType'
    }
    ruleset('rulesets/dry.xml') {
        exclude 'DuplicateNumberLiteral'
        exclude 'DuplicateStringLiteral'
    }
    ruleset('rulesets/exceptions.xml')
    ruleset('rulesets/formatting.xml') {
        'LineLength' { length = 120 }
        exclude 'ClassStartsWithBlankLine'
        exclude 'ClassEndsWithBlankLine'
        exclude 'SpaceAroundMapEntryColon'
    }
    ruleset('rulesets/groovyism.xml')
    ruleset('rulesets/imports.xml')
    ruleset('rulesets/jdbc.xml')
    ruleset('rulesets/logging.xml')
    ruleset('rulesets/naming.xml') {
        exclude 'FactoryMethodName'
    }
    ruleset('rulesets/security.xml') {
        exclude 'JavaIoPackageAccess'
    }
    ruleset('rulesets/generic.xml') {
        include 'IllegalClassReference'
        // @Grab resolves dependencies at compile time; Eval runs its argument (docs/practices.md, section 7)
        'IllegalClassReference' { priority = 1; classNames = 'groovy.lang.Grab, groovy.lang.Grapes, groovy.util.Eval' }
    }
    ruleset('rulesets/serialization.xml')
    ruleset('rulesets/size.xml') {
        'MethodSize' { maxLines = 60 }
        'CyclomaticComplexity' { maxMethodComplexity = 12 }
        'ParameterCount' { maxParameters = 5 }
        'NestedBlockDepth' { maxNestedBlockDepth = 4 }
        exclude 'CrapMetric'     // needs a Cobertura report
    }
    ruleset('rulesets/unnecessary.xml') {
        exclude 'UnnecessaryReturnKeyword'  // explicit returns preferred in typed code
        exclude 'UnnecessaryGetter'
    }
    ruleset('rulesets/unused.xml')
    ruleset('rulesets/enhanced.xml')        // requires the compile classpath (the engine supplies it)
}
