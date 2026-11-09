package net.plumt.ads.compose

import android.view.View
import net.plumt.ads.AdErrorReason
import net.plumt.ads.AdsErrorKind
import net.plumt.ads.AdsException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.0 의 Compose 공개 값 타입: 빌더·오버로드·일반 클래스의 equals. */
class ComposeApiShapeTest {
    @Test fun viewIdsBuilderKeepsIdsAndDefaultsToNoId() {
        val ids = NativeAdViewIds.builder()
            .headline(1).body(2).callToAction(3).advertiser(4).price(5).store(6).starRating(7).icon(8).media(9)
            .build()
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9), listOf(ids.headline, ids.body, ids.callToAction, ids.advertiser, ids.price, ids.store, ids.starRating, ids.icon, ids.media))
        val empty = NativeAdViewIds.builder().build()
        assertEquals(View.NO_ID, empty.media)
        assertEquals(View.NO_ID, empty.headline)
        assertEquals(empty, NativeAdViewIds.builder().build())
        assertEquals(empty.hashCode(), NativeAdViewIds.builder().build().hashCode())
        assertNotEquals(empty, ids)
        assertFalse(empty.equals("x"))
        assertTrue(ids.toString().contains("media=9"))
    }

    @Test fun interleaveOverloadsFillDefaults() {
        assertEquals(5, AdInterleave(5).firstAdAfter)
        assertEquals(Int.MAX_VALUE, AdInterleave(5).maxAds)
        assertEquals(3, AdInterleave(5, 3).firstAdAfter)
        assertEquals(Int.MAX_VALUE, AdInterleave(5, 3).maxAds)
        assertEquals(2, AdInterleave(5, 3, 2).maxAds)
    }

    @Test fun slotStatesCompareByContent() {
        val e = AdsException(AdsErrorKind.Load, 3, "none", null, AdErrorReason.NoFill)
        assertEquals(AdSlotState.Failed(e), AdSlotState.Failed(e))
        assertEquals(AdSlotState.Failed(e).hashCode(), e.hashCode())
        assertNotEquals(AdSlotState.Failed(e), AdSlotState.Loading)
        assertTrue(AdSlotState.Failed(e).toString().contains("none"))
        assertEquals("Idle", AdSlotState.Idle.toString())
        assertEquals("KeepSpace", AdSlotFailure.KeepSpace.toString())
    }
}
