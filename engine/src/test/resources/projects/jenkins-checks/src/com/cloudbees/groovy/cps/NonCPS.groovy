package com.cloudbees.groovy.cps

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

// A stand-in for the workflow-cps annotation, so the fixture compiles without the Jenkins classpath.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface NonCPS {
}
