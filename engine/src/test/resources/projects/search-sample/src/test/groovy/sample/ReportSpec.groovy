package sample

class ReportSpec {

    void rendersHeaderAndFooter() {
        assert new Report().render([:]) == 'headerfooter'
    }
}
