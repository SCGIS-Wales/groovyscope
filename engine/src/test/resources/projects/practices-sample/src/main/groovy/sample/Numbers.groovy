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
}
