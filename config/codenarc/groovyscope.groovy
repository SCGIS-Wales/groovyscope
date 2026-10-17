ruleset {
    description 'groovyscope defensive profile for Groovy 5.x and 6.x (CodeNarc 4.x)'

    ruleset('rulesets/basic.xml')
    ruleset('rulesets/braces.xml')
    ruleset('rulesets/concurrency.xml')
    ruleset('rulesets/convention.xml') {
        'CompileStatic' { priority = 2 }
        'NoDef' { priority = 2; doNotApplyToFilesMatching = '.*/(vars|scripts)/.*\\.groovy' }
        exclude 'TrailingComma'
        exclude 'ImplicitClosureParameter'
        exclude 'IfStatementCouldBeTernary'
        exclude 'CouldBeSwitchStatement'
    }
    ruleset('rulesets/design.xml') {
        exclude 'Instanceof'
        exclude 'NestedForLoop'
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
    ruleset('rulesets/security.xml')
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
    }
    ruleset('rulesets/unused.xml')
    ruleset('rulesets/enhanced.xml')        // requires the compile classpath (the engine supplies it)
}
