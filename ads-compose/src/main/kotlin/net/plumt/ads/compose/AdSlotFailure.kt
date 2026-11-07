package net.plumt.ads.compose

import androidx.compose.ui.unit.Dp
import net.plumt.ads.AdsException

/** 광고 슬롯이 광고를 받지 못했을 때(불러오기 실패·채울 광고 없음) 화면에서 어떻게 할지. */
public enum class AdSlotFailure {
    /** 자리를 접는다(높이 0). 주변 콘텐츠가 당겨 올라온다. 목록 사이 광고에 알맞다. */
    Collapse,

    /** 잡아 둔 자리를 그대로 둔다. 하단 고정 배너처럼 레이아웃이 흔들리면 안 되는 곳에 알맞다. */
    KeepSpace,
}

/** 슬롯 하나의 불러오기 상태. 호출부가 실패 화면·로그를 정할 때 읽는다. */
public sealed class AdSlotState {
    /** 쓸 수 없는 상태(스위치 꺼짐·동의 전·설정 오류)라 요청하지 않았다. */
    public data object Idle : AdSlotState()

    /** 불러오는 중. */
    public data object Loading : AdSlotState()

    /** 광고가 있다. */
    public data object Loaded : AdSlotState()

    /** 실패했다. 재시도할지는 [AdsException.reason] 에 따라 라이브러리가 정한다. */
    public data class Failed(val error: AdsException) : AdSlotState()
}

/** 슬롯이 그릴 것. */
internal enum class SlotDraw { Ad, Placeholder, Nothing }

/**
 * 광고 유무·상태·실패 처리로 슬롯이 그릴 것을 정한다.
 * 광고가 있으면 광고, 불러오는 중이면 자리표시(있으면), 실패면 [AdSlotFailure.KeepSpace] 일 때만 자리표시.
 */
internal fun slotDraw(hasAd: Boolean, state: AdSlotState, failure: AdSlotFailure, hasPlaceholder: Boolean): SlotDraw = when {
    hasAd -> SlotDraw.Ad
    !hasPlaceholder -> SlotDraw.Nothing
    state == AdSlotState.Loading -> SlotDraw.Placeholder
    state is AdSlotState.Failed && failure == AdSlotFailure.KeepSpace -> SlotDraw.Placeholder
    else -> SlotDraw.Nothing
}

/**
 * 동영상 광고는 미디어 뷰 없이 보일 수 없다(정책). [mediaHeight] 가 null 이거나 정책 최소(120dp)보다 작으면
 * 그 레이아웃으로는 동영상 광고를 그리지 않는다.
 */
internal fun canDrawNative(hasVideo: Boolean, mediaHeight: Dp?): Boolean =
    !hasVideo || (mediaHeight != null && mediaHeight >= MinMediaHeight)


/** 미디어 뷰 정책 최소 크기(가로·세로 120dp). */
internal val MinMediaHeight: Dp = androidx.compose.ui.unit.Dp(120f)
