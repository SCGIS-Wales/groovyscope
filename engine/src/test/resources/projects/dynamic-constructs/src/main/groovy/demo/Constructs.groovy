package demo

import groovy.transform.CompileDynamic

@CompileDynamic
class Shouting {
    static String shout(String self) {
        self.toUpperCase()
    }
}

@CompileDynamic
class Helper {
    String help() {
        'help'
    }
}

@CompileDynamic
@Mixin(Helper)
class Mixed {
}

@CompileDynamic
class Wrapper {
    @Delegate
    List<String> items = []
}

@CompileDynamic
class Intercepted implements GroovyInterceptable {
}

@CompileDynamic
class Constructs {
    void run(Object target, String name, String className) {
        target."$name"()
        use(Shouting) {
            'hi'.shout()
        }
        def bag = new Expando()
        bag.size = 1
        ExpandoMetaClass.enableGlobally()
        Class.forName(className)
    }
}
