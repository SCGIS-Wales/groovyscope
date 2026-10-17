package sample

class Report {

    String render(Map<String, Object> data) {
        StringBuilder out = new StringBuilder()
        out.append('header')
        data.each { k, v ->
            out.append(k).append('=').append(v)
        }
        out.append('footer')
        return out.toString()
    }

    String renderAgain(Map<String, Object> data) {
        StringBuilder out = new StringBuilder()
        out.append('header')
        data.each { k, v ->
            out.append(k).append('=').append(v)
        }
        out.append('footer')
        return out.toString()
    }

    String label(String text) {
        return '[' + text + ']'
    }

    String label(int number) {
        return '#' + number
    }
}
