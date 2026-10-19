package sample

class Numbers {
    int half(int total, int parts) {
        int result = total / parts
        return result
    }

    BigDecimal ratio(BigDecimal a, BigDecimal b) {
        return a / b
    }

    int pageSize(Integer requested) {
        return requested ?: 50
    }

    String label(Object value) {
        return value ?: 'none'
    }

    int count(List<String> items) {
        int n = items?.size()
        return n
    }

    String plain(String given) {
        return given ?: ''
    }

    List<String> none(List<String> given) {
        return given ?: []
    }

    Map<String, String> options(Map<String, String> given) {
        return given ?: [:]
    }

    long nothing(Long given) {
        return given ?: 0L
    }

    Object read(String body) {
        return new groovy.json.JsonSlurper().parseText(body ?: '{}')
    }
}
