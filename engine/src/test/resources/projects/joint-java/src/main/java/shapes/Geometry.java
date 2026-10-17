package shapes;

import java.util.List;

/** Static helpers written in Java; the inner class uses Java-only syntax Groovy rejects. */
public final class Geometry {
    private Geometry() {
    }

    public static double total(List<? extends Shape> shapes) {
        double sum = 0;
        for (Shape shape : shapes) {
            sum += shape.area();
        }
        return sum;
    }

    final class Tracker {
        Geometry owner() {
            return Geometry.this;
        }
    }
}
