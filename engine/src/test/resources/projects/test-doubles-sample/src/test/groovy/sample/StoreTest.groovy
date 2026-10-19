package sample

import org.junit.jupiter.api.Test

class StoreTest {

    Store store = spy(new Store())
    def cache = mock(Cache)

    @Test
    void stubsAPrivateMethod() {
        doReturn('x').when(store).fetch('k')
        assert store.load('k') == 'xK'
    }

    @Test
    void stubsSeams() {
        doReturn('x').when(store).format('k')
        when(cache.entries()).thenReturn([])
        checkLoaded()
    }

    @Test
    void overridesThroughTheMetaClass() {
        Store.metaClass.format = { String key -> 'y' }
        store.metaClass.load = { String key -> 'z' }
        Cache.metaClass.size = { -> 0 }
        assertEquals('z', store.load('k'))
    }

    @Test
    void runsWithoutChecking() {
        store.load('k')
    }

    @Test
    void stubsAPrivateMethodTheOtherWay() {
        Cache local = Mockito.spy(new Cache())
        when(local.evict()).thenReturn(null)
        given(cache.evict()).willReturn(null)
        expect(local.size())
    }

    @Test
    void usesAnInheritedHelper() {
        inheritedCheck(store)
    }

    private void checkLoaded() {
        verify(store).format('k')
    }

    private static void expect(int size) {
        if (size < 0) {
            throw new AssertionError('negative')
        }
    }
}
