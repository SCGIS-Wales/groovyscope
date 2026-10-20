package demo

class Calculator {
    int add(int a, int b) {
        a + b
    }

    int sum(List<Integer> values) {
        int total = 0
        for (Integer value : values) {
            total = add(total, value)
        }
        total
    }
}

class Report {
    String render(Calculator calculator) {
        'total ' + calculator.sum([1, 2])
    }
}
