package net.plumt.ads.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BannerLayoutTest {
    @Test fun usesParentWidthNotScreenWidth() {
        // 화면 411dp 안에 좌우 16dp 패딩: 배너는 379dp 로 만들어야 자리를 넘지 않는다
        assertEquals(379, bannerWidthDp(379f, hasBoundedWidth = true, screenWidthDp = 411))
    }

    @Test fun floorsFractionalWidth() {
        assertEquals(320, bannerWidthDp(320.9f, true, 411))
    }

    @Test fun unboundedFallsBackToScreenWidth() {
        assertEquals(411, bannerWidthDp(Float.POSITIVE_INFINITY, hasBoundedWidth = false, screenWidthDp = 411))
        assertEquals(411, bannerWidthDp(Float.POSITIVE_INFINITY, hasBoundedWidth = true, screenWidthDp = 411))
        assertEquals(411, bannerWidthDp(Float.NaN, hasBoundedWidth = true, screenWidthDp = 411))
        assertNull(bannerWidthDp(Float.POSITIVE_INFINITY, false, 0))
    }

    @Test fun notYetMeasuredCreatesNothing() {
        assertNull(bannerWidthDp(0f, true, 411))
        assertNull(bannerWidthDp(0.5f, true, 411))
        assertEquals(1, bannerWidthDp(1f, true, 411))
    }

    @Test fun heightKeptAfterFirstLoadEvenIfRefreshFails() {
        // 첫 로드 전(예약 안 함): 0
        assertEquals(0, bannerHeightDp(50, reserveSpace = false, loaded = false))
        // 한 번 왔으면 이후 갱신 실패와 무관하게 유지
        assertEquals(50, bannerHeightDp(50, reserveSpace = false, loaded = true))
    }

    @Test fun reservedSpaceFromStart() {
        assertEquals(62, bannerHeightDp(62, reserveSpace = true, loaded = false))
        assertEquals(0, bannerHeightDp(-3, reserveSpace = true, loaded = true))
    }

    @Test fun offlineCollapseFoldsEvenAfterAnAdWasShown() {
        // 오프라인으로 뷰를 떼면 Collapse 는 광고를 보인 적이 있어도 접는다
        assertEquals(0, bannerHeightDp(50, reserveSpace = true, loaded = false, failure = AdSlotFailure.Collapse, attached = false, shownBefore = true))
        assertEquals(0, bannerHeightDp(50, reserveSpace = false, loaded = false, failure = AdSlotFailure.Collapse, attached = false))
    }

    @Test fun offlineKeepSpaceHoldsTheAdHeight() {
        // 광고를 보인 자리, 또는 미리 잡은 자리는 빈 채로 지킨다
        assertEquals(50, bannerHeightDp(50, reserveSpace = false, loaded = false, failure = AdSlotFailure.KeepSpace, attached = false, shownBefore = true))
        assertEquals(50, bannerHeightDp(50, reserveSpace = true, loaded = false, failure = AdSlotFailure.KeepSpace, attached = false))
        // 예약도 없고 보인 적도 없으면 자리를 만들지 않는다
        assertEquals(0, bannerHeightDp(50, reserveSpace = false, loaded = false, failure = AdSlotFailure.KeepSpace, attached = false))
        // 한 번도 뷰를 만들지 못해 높이를 모르면 0
        assertEquals(0, bannerHeightDp(0, reserveSpace = true, loaded = false, failure = AdSlotFailure.KeepSpace, attached = false))
    }

    @Test fun detachedViewLoadedFlagDoesNotCount() {
        // 뗀 뷰의 loaded 는 보이는 광고가 아니다
        assertEquals(0, bannerHeightDp(50, reserveSpace = false, loaded = true, failure = AdSlotFailure.Collapse, attached = false))
    }

    @Test fun firstLoadFailureFollowsFailurePolicy() {
        assertEquals(0, bannerHeightDp(50, reserveSpace = true, loaded = false, failed = true, failure = AdSlotFailure.Collapse))
        assertEquals(50, bannerHeightDp(50, reserveSpace = true, loaded = false, failed = true, failure = AdSlotFailure.KeepSpace))
        assertEquals(0, bannerHeightDp(50, reserveSpace = false, loaded = false, failed = true, failure = AdSlotFailure.KeepSpace))
    }

    @Test fun recreatedViewLoadingAfterReconnect() {
        // 다시 만든 뷰가 아직 광고를 못 받았을 때: KeepSpace 는 보였던 자리를 지키고, Collapse 는 예약 여부를 따른다
        assertEquals(50, bannerHeightDp(50, reserveSpace = false, loaded = false, failure = AdSlotFailure.KeepSpace, shownBefore = true))
        assertEquals(0, bannerHeightDp(50, reserveSpace = false, loaded = false, failure = AdSlotFailure.Collapse, shownBefore = true))
        assertEquals(50, bannerHeightDp(50, reserveSpace = true, loaded = false, failure = AdSlotFailure.Collapse, shownBefore = true))
        // 다시 받으면 정책과 무관하게 광고 높이
        assertEquals(50, bannerHeightDp(50, reserveSpace = false, loaded = true, failure = AdSlotFailure.Collapse))
    }
}
