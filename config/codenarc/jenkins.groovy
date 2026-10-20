ruleset {
    description 'groovyscope profile for Jenkins pipeline code (CPS Groovy runtime, not Groovy 5/6)'

    ruleset('rulesets/basic.xml')
    ruleset('rulesets/braces.xml')
    ruleset('rulesets/exceptions.xml')
    ruleset('rulesets/formatting.xml') {
        'LineLength' { length = 120 }
        exclude 'ClassStartsWithBlankLine'
        exclude 'ClassEndsWithBlankLine'
        exclude 'SpaceAroundMapEntryColon'
    }
    ruleset('rulesets/groovyism.xml')
    ruleset('rulesets/imports.xml')
    ruleset('rulesets/jenkins.xml')
    ruleset('rulesets/security.xml')
    ruleset('rulesets/size.xml') {
        'MethodSize' { maxLines = 60 }
        'CyclomaticComplexity' { maxMethodComplexity = 12 }
        'ParameterCount' { maxParameters = 5 }
        'NestedBlockDepth' { maxNestedBlockDepth = 4 }
        exclude 'CrapMetric'     // needs a Cobertura report
    }
    ruleset('rulesets/unnecessary.xml') {
        exclude 'UnnecessaryReturnKeyword'
    }
    ruleset('rulesets/unused.xml')
}
