package net.plumt.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class IntervalFrequencyPolicyTest {
    private val clock = FakeClock(1_000)

    private fun policy(min: Long = 100, delay: Long = 50, max: Int = Int.MAX_VALUE) =
        IntervalFrequencyPolicy(min, delay, max, clock)

    @Test fun blocksDuringInitialDelay() {
        val p = policy()
        assertFalse(p.canShow("a"))
        clock.advance(49)
        assertFalse(p.canShow("a"))
        clock.advance(1)
        assertTrue(p.canShow("a"))
    }

    @Test fun zeroDelayAllowsImmediately() {
        assertTrue(policy(delay = 0).canShow("a"))
    }

    @Test fun minIntervalBlocksUntilElapsed() {
        val p = policy(delay = 0)
        p.onShown("a")
        assertFalse(p.canShow("a"))
        clock.advance(99)
        assertFalse(p.canShow("a"))
        clock.advance(1)
        assertTrue(p.canShow("a"))
    }

    @Test fun intervalIsSharedAcrossPlacements() {
        val p = policy(delay = 0)
        p.onShown("a")
        assertFalse(p.canShow("b"))
    }

    @Test fun maxShowsCapsForever() {
        val p = policy(min = 0, delay = 0, max = 2)
        p.onShown("a"); p.onShown("a")
        assertEquals(2, p.shownCount)
        clock.advance(1_000_000)
        assertFalse(p.canShow("a"))
    }

    @Test fun canShowDoesNotMutate() {
        val p = policy(delay = 0)
        repeat(5) { assertTrue(p.canShow("a")) }
        assertEquals(0, p.shownCount)
    }

    @Test fun defaultsAreDocumentedValues() {
        assertEquals(120_000L, IntervalFrequencyPolicy.DEFAULT_MIN_INTERVAL_MILLIS)
        assertEquals(30_000L, IntervalFrequencyPolicy.DEFAULT_INITIAL_DELAY_MILLIS)
    }

    @Test fun rejectsBadArguments() {
        assertEquals(
            "minIntervalMillis 는 0 이상이어야 합니다: -1",
            assertThrows(IllegalArgumentException::class.java) { IntervalFrequencyPolicy(-1, 0, 1, clock) }.message,
        )
        assertEquals(
            "initialDelayMillis 는 0 이상이어야 합니다: -5",
            assertThrows(IllegalArgumentException::class.java) { IntervalFrequencyPolicy(0, -5, 1, clock) }.message,
        )
        assertEquals(
            "maxShows 는 1 이상이어야 합니다: 0",
            assertThrows(IllegalArgumentException::class.java) { IntervalFrequencyPolicy(0, 0, 0, clock) }.message,
        )
    }

    @Test fun clockMovingBackwardsNeverAllowsEarly() {
        val p = policy(delay = 0)
        p.onShown("a")
        clock.now -= 10_000
        assertFalse(p.canShow("a"))
    }

    @Test fun fuzzNeverViolatesInterval() {
        val rnd = Random(42)
        val p = policy(min = 100, delay = 0, max = 50)
        var lastShown = -1_000_000L
        var shows = 0
        repeat(5_000) {
            clock.advance(rnd.nextInt(150).toLong())
            if (p.canShow("x")) {
                assertTrue(clock.now - lastShown >= 100)
                p.onShown("x")
                lastShown = clock.now
                shows++
            }
        }
        assertTrue(shows <= 50)
        assertEquals(shows, p.shownCount)
    }
}

class AdFrequencyPolicyCombinatorTest {
    private class Spy(var allow: Boolean) : AdFrequencyPolicy {
        val shown = mutableListOf<String>()
        override fun canShow(placement: String) = allow
        override fun onShown(placement: String) { shown += placement }
    }

    @Test fun unlimitedAlwaysAllows() {
        AdFrequencyPolicy.Unlimited.onShown("a")
        assertTrue(AdFrequencyPolicy.Unlimited.canShow("a"))
    }

    @Test fun allRequiresEveryPolicy() {
        val a = Spy(true); val b = Spy(false)
        val all = AdFrequencyPolicy.all(a, b)
        assertFalse(all.canShow("p"))
        b.allow = true
        assertTrue(all.canShow("p"))
    }

    @Test fun allWithNoPoliciesAllows() {
        assertTrue(AdFrequencyPolicy.all().canShow("p"))
    }

    @Test fun allForwardsShownToEveryone() {
        val a = Spy(true); val b = Spy(true)
        AdFrequencyPolicy.all(a, b).onShown("p")
        assertEquals(listOf("p"), a.shown)
        assertEquals(listOf("p"), b.shown)
    }
}

class ValueTypesTest {
    @Test fun audienceDefaultsUnspecified() {
        assertEquals(AdAudience(AdFlag.Unspecified, AdFlag.Unspecified, AdContentRating.Unspecified), AdAudience.Default)
    }

    @Test fun audienceForTarget() {
        assertEquals(AdAudience(AdFlag.Yes, AdFlag.Yes, AdContentRating.G), AdAudience.forTarget(AdTarget.AllAges))
        assertEquals(AdAudience(AdFlag.No, AdFlag.Unspecified, AdContentRating.T), AdAudience.forTarget(AdTarget.Teen))
        assertEquals(AdAudience(AdFlag.No, AdFlag.No, AdContentRating.MA), AdAudience.forTarget(AdTarget.Adult))
    }

    @Test fun configRejectsBlankTestDevice() {
        val e = assertThrows(IllegalArgumentException::class.java) { AdsConfig(testDeviceIds = listOf("ABC", " ")) }
        assertEquals("testDeviceIds 에 빈 문자열이 있습니다: [ABC,  ]", e.message)
    }

    @Test fun configAcceptsUnicodeAndLongIds() {
        val c = AdsConfig(testDeviceIds = listOf("기기-1", "x".repeat(10_000)))
        assertEquals(2, c.testDeviceIds.size)
        assertEquals(ConsentDebugGeography.Disabled, c.consentDebugGeography)
    }

    @Test fun exceptionKeepsKindCodeCause() {
        val cause = RuntimeException("원인")
        val e = AdsException(AdsErrorKind.Show, 3, "실패", cause)
        assertEquals(AdsErrorKind.Show, e.kind)
        assertEquals(3, e.code)
        assertSame(cause, e.cause)
        assertEquals("[Show/3] 실패", e.message)
        assertEquals(-1, AdsException.CODE_UNKNOWN)
    }

    @Test fun preloadPolicyValidation() {
        assertEquals(
            "maxRetries 는 0 이상이어야 합니다: -1",
            assertThrows(IllegalArgumentException::class.java) { PreloadPolicy(maxRetries = -1) }.message,
        )
        assertEquals(
            "retryBackoffMillis 는 0 이상이어야 합니다: -1",
            assertThrows(IllegalArgumentException::class.java) { PreloadPolicy(retryBackoffMillis = -1) }.message,
        )
        assertEquals(
            "expireAfterMillis 는 1 이상이어야 합니다: 0",
            assertThrows(IllegalArgumentException::class.java) { PreloadPolicy(expireAfterMillis = 0) }.message,
        )
        PreloadPolicy(maxRetries = 0, retryBackoffMillis = 0, expireAfterMillis = 1)
    }

    @Test fun eventListenerCombineCallsInOrder() {
        val order = mutableListOf<Int>()
        val ev = AdEvent.Loaded(AdFormat.Banner, "p")
        AdEventListener.combine(AdEventListener { order += 1 }, AdEventListener { order += 2 }).onEvent(ev)
        assertEquals(listOf(1, 2), order)
        AdEventListener.None.onEvent(ev)
        AdEventListener.combine().onEvent(ev)
    }

    @Test fun eventsExposeFormatAndPlacement() {
        val events = listOf<AdEvent>(
            AdEvent.Requested(AdFormat.Native, "p"), AdEvent.Loaded(AdFormat.Native, "p"),
            AdEvent.LoadFailed(AdFormat.Native, "p", 1, "m"), AdEvent.Impression(AdFormat.Native, "p"),
            AdEvent.Clicked(AdFormat.Native, "p"), AdEvent.Shown(AdFormat.Native, "p"),
            AdEvent.ShowFailed(AdFormat.Native, "p", 1, "m"), AdEvent.Dismissed(AdFormat.Native, "p"),
            AdEvent.RewardEarned(AdFormat.Native, "p", "coin", 5), AdEvent.Suppressed(AdFormat.Native, "p", SuppressReason.Unavailable),
        )
        events.forEach { assertEquals(AdFormat.Native, it.format); assertEquals("p", it.placement); assertNotNull(it.toString()) }
        assertEquals(AdEvent.LoadFailed(AdFormat.Banner, "q", 2, "x"), AdEvent.LoadFailed(AdFormat.Banner, "q", 2, "x"))
    }
}
