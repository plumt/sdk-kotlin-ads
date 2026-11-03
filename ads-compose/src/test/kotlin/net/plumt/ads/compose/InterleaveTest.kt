package net.plumt.ads.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class InterleaveTest {
    /** 단순 시뮬레이션: 콘텐츠를 하나씩 놓으며 규칙대로 광고를 끼운다. */
    private fun brute(spec: AdInterleave, n: Int): List<Any> {
        val out = mutableListOf<Any>()
        var sinceAd = 0
        var ads = 0
        for (c in 0 until n) {
            val threshold = if (ads == 0) spec.firstAdAfter else spec.interval
            if (sinceAd == threshold && ads < spec.maxAds) {
                out += "ad$ads"; ads++; sinceAd = 0
            }
            out += c; sinceAd++
        }
        return out
    }

    private fun assertMatchesBrute(spec: AdInterleave, n: Int) {
        val expected = brute(spec, n)
        val plan = spec.plan(n)
        assertEquals("total n=$n", expected.size, plan.totalCount)
        assertEquals("adCount n=$n", expected.count { it is String }, plan.adCount)
        expected.forEachIndexed { i, e ->
            if (e is String) {
                assertTrue("isAd $i", plan.isAd(i))
                assertEquals(e.removePrefix("ad").toInt(), plan.adSlot(i))
            } else {
                assertFalse("isAd $i", plan.isAd(i))
                assertEquals(e, plan.contentIndex(i))
                assertEquals(i, plan.listIndexOfContent(e as Int))
            }
        }
    }

    @Test fun docExample() {
        val plan = AdInterleave(interval = 5, firstAdAfter = 3).plan(12)
        // 콘텐츠 0,1,2 · 광고 · 콘텐츠 3..7 · 광고 · 콘텐츠 8..11
        assertTrue(plan.isAd(3))
        assertTrue(plan.isAd(9))
        assertEquals(2, plan.adCount)
        assertEquals(14, plan.totalCount)
        assertEquals(3, plan.contentIndex(4))
    }

    @Test fun noTrailingAd() {
        // 콘텐츠가 정확히 firstAdAfter 개면 뒤에 콘텐츠가 없으니 광고 없음
        assertEquals(0, AdInterleave(3).plan(3).adCount)
        assertEquals(1, AdInterleave(3).plan(4).adCount)
        assertEquals(1, AdInterleave(3).plan(6).adCount)
        assertEquals(2, AdInterleave(3).plan(7).adCount)
    }

    @Test fun emptyAndSmallLists() {
        for (n in 0..3) {
            val plan = AdInterleave(5).plan(n)
            assertEquals(0, plan.adCount)
            assertEquals(n, plan.totalCount)
            assertEquals(n, plan.contentCount)
        }
    }

    @Test fun maxAdsCaps() {
        val plan = AdInterleave(interval = 1, firstAdAfter = 1, maxAds = 2).plan(100)
        assertEquals(2, plan.adCount)
        assertEquals(102, plan.totalCount)
        assertMatchesBrute(AdInterleave(1, 1, 2), 100)
    }

    @Test fun maxAdsZeroMeansNoAds() {
        val plan = AdInterleave(1, 1, 0).plan(10)
        assertEquals(0, plan.adCount)
        (0 until 10).forEach { assertFalse(plan.isAd(it)); assertEquals(it, plan.contentIndex(it)) }
    }

    @Test fun intervalOne() = assertMatchesBrute(AdInterleave(1), 20)

    @Test fun exhaustiveSmallGrid() {
        for (interval in 1..6) for (first in 1..6) for (max in listOf(0, 1, 2, 3, Int.MAX_VALUE)) for (n in 0..40) {
            assertMatchesBrute(AdInterleave(interval, first, max), n)
        }
    }

    @Test fun fuzzSeeded() {
        val rnd = Random(20261102)
        repeat(300) {
            val spec = AdInterleave(1 + rnd.nextInt(50), 1 + rnd.nextInt(50), if (rnd.nextBoolean()) Int.MAX_VALUE else rnd.nextInt(10))
            assertMatchesBrute(spec, rnd.nextInt(1_000))
        }
    }

    @Test fun veryLargeListsDoNotOverflow() {
        val plan = AdInterleave(interval = 1, firstAdAfter = 1).plan(Int.MAX_VALUE / 2)
        assertEquals(Int.MAX_VALUE / 2 - 1, plan.adCount)
        val capped = AdInterleave(1, 1, Int.MAX_VALUE).plan(1_000_000)
        assertEquals(999_999, capped.adCount)
    }

    @Test fun keysAreStableWhenContentAppended() {
        val spec = AdInterleave(3)
        val before = spec.plan(10)
        val after = spec.plan(11)
        // 뒤에 항목이 붙어도 기존 콘텐츠의 인덱스와 광고 슬롯 번호는 변하지 않는다
        for (c in 0 until 10) assertEquals(before.listIndexOfContent(c), after.listIndexOfContent(c))
        for (i in 0 until before.totalCount) if (before.isAd(i)) assertEquals(before.adSlot(i), after.adSlot(i))
    }

    @Test fun rejectsBadSpec() {
        assertEquals("interval 은 1 이상이어야 합니다: 0", assertThrows(IllegalArgumentException::class.java) { AdInterleave(0) }.message)
        assertEquals("firstAdAfter 는 1 이상이어야 합니다: 0", assertThrows(IllegalArgumentException::class.java) { AdInterleave(2, 0) }.message)
        assertEquals("maxAds 는 0 이상이어야 합니다: -1", assertThrows(IllegalArgumentException::class.java) { AdInterleave(2, 2, -1) }.message)
        assertEquals("contentCount 는 0 이상이어야 합니다: -1", assertThrows(IllegalArgumentException::class.java) { AdInterleave(2).plan(-1) }.message)
    }

    @Test fun rejectsBadIndexes() {
        val plan = AdInterleave(2).plan(4) // 총 5: c c A c c
        assertEquals("index 는 0..4 이어야 합니다: 5", assertThrows(IllegalArgumentException::class.java) { plan.isAd(5) }.message)
        assertThrows(IllegalArgumentException::class.java) { plan.isAd(-1) }
        assertEquals("광고 자리가 아닙니다: 0", assertThrows(IllegalArgumentException::class.java) { plan.adSlot(0) }.message)
        assertEquals("콘텐츠 자리가 아닙니다: 2", assertThrows(IllegalArgumentException::class.java) { plan.contentIndex(2) }.message)
        assertEquals("contentIndex 는 0..3 이어야 합니다: 4", assertThrows(IllegalArgumentException::class.java) { plan.listIndexOfContent(4) }.message)
        assertThrows(IllegalArgumentException::class.java) { plan.listIndexOfContent(-1) }
    }

    @Test fun emptyPlanRejectsEveryIndex() {
        val plan = AdInterleave(2).plan(0)
        assertThrows(IllegalArgumentException::class.java) { plan.isAd(0) }
        assertThrows(IllegalArgumentException::class.java) { plan.listIndexOfContent(0) }
    }

    @Test fun keyPrefixIsStable() {
        assertEquals("net.plumt.ads.slot:", AD_ITEM_KEY_PREFIX)
    }
}
