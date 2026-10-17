package evil

@Grab('commons-lang:commons-lang:2.6')
import org.apache.commons.lang.StringUtils

class UsesGrab {
    String x() {
        StringUtils.capitalize('a')
    }
}
