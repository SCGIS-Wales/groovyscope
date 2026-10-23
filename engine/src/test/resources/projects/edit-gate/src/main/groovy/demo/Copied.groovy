package demo

class Copied {

    String pageName(String title) {
        String lower = title.toLowerCase()
        String dashed = lower.replaceAll(/[^a-z0-9]+/, '-')
        String trimmed = dashed.replaceAll(/^-+|-+$/, '')
        return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed
    }
}
