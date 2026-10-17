package demo

class DemoPluginTest {
    void stubs(DemoPlugin plugin) {
        org.mockito.Mockito.doNothing().when(plugin).seam()
    }
}
