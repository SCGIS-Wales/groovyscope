package shapes

import groovy.transform.CompileStatic

@CompileStatic
class Report {
    String summarise(List<Shape> shapes) {
        "total ${Geometry.total(shapes)}"
    }
}
