package demo

class Order {
    String id
}

class Repository {
    void save(String key) {
    }

    void save(Order order) {
    }
}

class AuditedRepository extends Repository {
    @Override
    void save(Order order) {
    }
}

abstract class Store<T> {
    abstract void put(T item)
}

class OrderStore extends Store<Order> {
    @Override
    void put(Order item) {
    }
}

class Service {
    Repository repository = new Repository()

    void storeKey() {
        repository.save('k1')
    }

    void storeOrder() {
        repository.save(new Order(id: 'o1'))
    }

    void storeAny(value) {
        repository.save(value)
    }
}
