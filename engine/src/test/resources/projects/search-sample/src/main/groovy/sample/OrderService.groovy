package sample

import java.util.logging.Logger

class OrderService {

    private static final Logger log = Logger.getLogger('orders')
    static final String PREFIX = 'ORD-'
    def cache = [:]
    private final List<String> names = []

    /**
     * Totals an order.
     */
    @Deprecated
    BigDecimal total(List<BigDecimal> prices, BigDecimal discount) {
        BigDecimal sum = 0
        for (BigDecimal price : prices) {
            sum = sum + price
        }
        if (discount != null) {
            sum = sum - discount
        }
        log.info('total computed')
        return sum
    }

    BigDecimal invoiceTotal(List<BigDecimal> amounts, BigDecimal rebate) {
        BigDecimal value = 0
        for (BigDecimal amount : amounts) {
            value = value + amount
        }
        if (rebate != null) {
            value = value - rebate
        }
        log.info('total computed')
        return value
    }
}
