package demo

class Types {

    boolean isAdult(String age) {
        return age > 18
    }

    def total(List<Integer> amounts) {
        def sum = 0
        if (amounts.isEmpty()) {
            sum = 'n/a'
        }
        return sum
    }

    int countItems(List<String> items) {
        return items.length
    }

    boolean enabled(Map settings) {
        String flag = settings.get('enabled')
        if (flag) {
            return true
        }
        return flag == 'true'
    }

    def status(int code) {
        if (code == 0) {
            return true
        }
        return 'failed: ' + code
    }

    Map options() {
        Map map = [:]
        map.retries = 3
        map.retries = 'three'
        return map
    }
}
