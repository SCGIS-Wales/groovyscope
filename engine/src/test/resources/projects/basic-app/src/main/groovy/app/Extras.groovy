package app

import static java.lang.Math.max

import app.model.Person

abstract class Base {
    String base() {
        'base'
    }

    abstract String label()
}

class Derived extends Base {
    Derived() {
        super()
    }

    Derived(String seed) {
        this()
    }

    String derived() {
        super.base() + this.base()
    }

    String label() {
        'derived'
    }

    static int twice(int n) {
        max(n, 0) * 2
    }
}

class Extras {
    Person person = new Person('Bob', 50)
    List<String> names = []
    String[] tags = new String[0]

    String demo() {
        String s = 'abc'
        int len = s.size()
        def d = new Derived('x')
        def d2 = new Derived()
        String chained = d.derived().toUpperCase()
        String viaProperty = person.name.toLowerCase()
        int t = Derived.twice(3)
        Person casted = (Person) null
        casted?.isAdult()
        names.add(chained)
        this.names.size()
        for (String tag : tags) {
            println tag
        }
        try {
            d2.label()
        } catch (IllegalStateException e) {
            println e.message
        }
        return viaProperty + len + t
    }
}
