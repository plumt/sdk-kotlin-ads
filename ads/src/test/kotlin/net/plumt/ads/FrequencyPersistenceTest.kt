package net.plumt.ads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/** 빈도 기록 저장소와, 프로세스 재시작(저장소·정책 재생성)을 넘는 빈도 제한. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 35])
class FrequencyPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(1_700_000_000_000)

    private fun prefs(name: String = "freq-test") = AdFrequencyStore.sharedPreferences(context, name)

    @Test fun inMemoryStoreRoundTrips() {
        val s = AdFrequencyStore.inMemory()
        assertNull(s.getLong("a"))
        s.putLong("a", 5)
        assertEquals(5L, s.getLong("a"))
        s.putLong("a", Long.MIN_VALUE)
        assertEquals(Long.MIN_VALUE, s.getLong("a"))
        assertNull(AdFrequencyStore.inMemory().getLong("a"))
    }

    @Test fun sharedPreferencesSurvivesNewInstance() {
        prefs().putLong("k", 42)
        assertEquals(42L, prefs().getLong("k"))
        assertNull(prefs().getLong("없는-키"))
        assertNull(prefs("다른-파일").getLong("k"))
        assertEquals(AdFrequencyStore.DEFAULT_FILE_NAME, "net.plumt.ads.frequency")
        AdFrequencyStore.sharedPreferences(context).putLong("유니코드-키-한글", 0)
        assertEquals(0L, AdFrequencyStore.sharedPreferences(context).getLong("유니코드-키-한글"))
    }

    @Test fun sharedPreferencesRejectsBlankName() {
        assertEquals(
            "fileName 이 비었습니다",
            assertThrows(IllegalArgumentException::class.java) { AdFrequencyStore.sharedPreferences(context, " ") }.message,
        )
    }

    @Test fun intervalSurvivesProcessRestart() {
        val p1 = interval(minIntervalMillis = 120_000, initialDelayMillis = 30_000, clock = clock, store = prefs())
        clock.advance(30_000)
        assertTrue(p1.canShow("a"))
        p1.onShown("a")

        // 프로세스가 죽고 다시 떴다: 저장소·정책을 새로 만든다
        clock.advance(40_000)
        val p2 = interval(minIntervalMillis = 120_000, initialDelayMillis = 30_000, clock = clock, store = prefs())
        clock.advance(30_000) // 시작 유예는 끝났지만 마지막 표시 뒤 70초
        assertFalse(p2.canShow("a"))
        clock.advance(49_999)
        assertFalse(p2.canShow("a"))
        clock.advance(1)
        assertTrue(p2.canShow("a"))
        assertEquals(0, p2.shownCount) // 세션 횟수는 저장하지 않는다
    }

    @Test fun keysSeparatePolicies() {
        val store = AdFrequencyStore.inMemory()
        val a = interval(100, 0, clock = clock, store = store, key = "a")
        val b = interval(100, 0, clock = clock, store = store, key = "b")
        a.onShown("x")
        assertFalse(a.canShow("x"))
        assertTrue(b.canShow("x"))
        assertEquals(clock.now, store.getLong("a.last_shown_at"))
        assertEquals(IntervalFrequencyPolicy.DEFAULT_KEY, interval(clock = clock).key)
    }

    @Test fun intervalRejectsBlankKey() {
        assertEquals(
            "key 가 비었습니다",
            assertThrows(IllegalArgumentException::class.java) { interval(clock = clock, key = "") }.message,
        )
    }

    @Test fun clockMovedBackRestartsCountingInsteadOfBlockingForever() {
        val store = AdFrequencyStore.inMemory()
        val p = interval(minIntervalMillis = 100, initialDelayMillis = 50, clock = clock, store = store)
        clock.advance(50)
        p.onShown("a")
        clock.now -= 86_400_000 // 하루 뒤로
        assertFalse(p.canShow("a")) // 일찍 허용하지 않는다
        clock.advance(99)
        assertFalse(p.canShow("a"))
        clock.advance(1)
        assertTrue("하루를 기다리지 않고 간격만큼만 막는다", p.canShow("a"))
    }

    @Test fun clockMovedBackWithZeroIntervalAllows() {
        val p = interval(minIntervalMillis = 0, initialDelayMillis = 0, clock = clock)
        p.onShown("a")
        clock.now -= 10
        assertTrue(p.canShow("a"))
    }

    @Test fun clockMovedBackDuringInitialDelayRestartsDelay() {
        val p = interval(minIntervalMillis = 0, initialDelayMillis = 100, clock = clock)
        clock.now -= 1_000_000
        assertFalse(p.canShow("a"))
        clock.advance(100)
        assertTrue(p.canShow("a"))
    }

    @Test fun fuzzAcrossRestartsNeverViolatesInterval() {
        val rnd = Random(7)
        val store = AdFrequencyStore.inMemory()
        fun newPolicy() = interval(minIntervalMillis = 1_000, initialDelayMillis = 200, clock = clock, store = store)
        var p = newPolicy()
        var lastShown: Long? = null
        repeat(5_000) {
            clock.advance(rnd.nextInt(400).toLong())
            if (rnd.nextInt(20) == 0) p = newPolicy() // 프로세스 재시작·회전
            if (p.canShow("x")) {
                lastShown?.let { assertTrue(clock.now - it >= 1_000) }
                p.onShown("x")
                lastShown = clock.now
            }
        }
        assertTrue(lastShown != null)
    }

    @Test fun launchCountSkipsFirstLaunches() {
        val store = prefs("launch-test")
        val first = LaunchCountFrequencyPolicy(skipFirstLaunches = 2, store = store)
        assertFalse(first.canShow("a"))
        assertFalse(first.canShow("a")) // 같은 실행은 한 번만 센다
        assertEquals(1L, first.launchNumber)
        val second = LaunchCountFrequencyPolicy(2, prefs("launch-test"))
        assertFalse(second.canShow("a"))
        val third = LaunchCountFrequencyPolicy(2, prefs("launch-test"))
        assertTrue(third.canShow("a"))
        assertEquals(3L, third.launchNumber)
        third.onShown("a")
        assertEquals(3L, prefs("launch-test").getLong(LaunchCountFrequencyPolicy.DEFAULT_KEY))
    }

    @Test fun launchCountZeroSkipAllowsFirstLaunch() {
        assertTrue(LaunchCountFrequencyPolicy(0, AdFrequencyStore.inMemory()).canShow("a"))
    }

    @Test fun launchCountSaturatesAndRepairsCorruptValues() {
        val store = AdFrequencyStore.inMemory()
        store.putLong("k", Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, LaunchCountFrequencyPolicy(1, store, "k").launchNumber)
        store.putLong("k", -50)
        assertEquals(1L, LaunchCountFrequencyPolicy(1, store, "k").launchNumber)
    }

    @Test fun launchCountRejectsBadArguments() {
        val store = AdFrequencyStore.inMemory()
        assertEquals(
            "skipFirstLaunches 는 0 이상이어야 합니다: -1",
            assertThrows(IllegalArgumentException::class.java) { LaunchCountFrequencyPolicy(-1, store) }.message,
        )
        assertEquals(
            "key 가 비었습니다",
            assertThrows(IllegalArgumentException::class.java) { LaunchCountFrequencyPolicy(0, store, " ") }.message,
        )
    }

    @Test fun wallClockIsEpochMillis() {
        val before = System.currentTimeMillis()
        val now = AdClock.Wall.nowMillis()
        assertTrue(now >= before && now <= System.currentTimeMillis())
    }
}
