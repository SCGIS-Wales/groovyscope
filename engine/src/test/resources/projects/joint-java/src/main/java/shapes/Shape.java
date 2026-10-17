package shapes;

/** A Java interface implemented in Groovy. */
public interface Shape {
    double area();

    default String describe() {
        return getClass().getSimpleName() + " with area " + area();
    }
}
