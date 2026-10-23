package demo

import groovy.transform.CompileStatic

@CompileStatic
class Verbose {

    // ==========================================================
    // Counting
    // ==========================================================

    private int counter

    /**
     * Returns the counter.
     * @return the counter
     */
    private int current() {
        return counter
    }

    int countWords(String text) {
        // check whether the text is null
        if (text == null) {
            // return zero
            return 0
        }
        // This splits the text on whitespace and then it counts
        // the parts that come out of the split, which gives the
        // number of words that the text has in it, as the caller
        // expects from a method that is called countWords.
        int words = text.trim().split(/\s+/).length
        // increment the counter
        counter++
        // int old = counter - 1
        // println(old)
        // TODO
        return words
    }
}
