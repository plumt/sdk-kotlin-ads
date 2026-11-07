package net.plumt.ads.compose

import kotlin.math.floor
import net.plumt.ads.network.BannerHandle

/**
 * 배너 크기를 정할 폭(dp). 부모가 실제로 준 폭을 쓴다 — 화면 폭으로 정하면 패딩·2단 레이아웃 안에서
 * 광고가 자리보다 커져 SDK 가 그리지 않는다. 폭 제한이 없으면 화면 폭, 아직 1dp 도 안 되면 null(만들지 않는다).
 */
internal fun bannerWidthDp(maxWidthDp: Float, hasBoundedWidth: Boolean, screenWidthDp: Int): Int? {
    if (!hasBoundedWidth || !maxWidthDp.isFinite()) return screenWidthDp.takeIf { it > 0 }
    return if (maxWidthDp >= 1f) floor(maxWidthDp).toInt() else null
}

/**
 * 배너 슬롯이 차지할 높이(dp).
 *
 * - 붙은 뷰에 광고가 왔으면([loaded]) 광고 높이 — 그 뒤 자동 갱신이 실패해도 SDK 가 이전 광고를 계속 보인다
 * - 광고를 보이지 못하는 상태(첫 광고 전 실패 [failed], 오프라인이라 뷰를 뗌 `![attached]`)에서 [failure] 가
 *   [AdSlotFailure.Collapse] 면 0
 * - 그 밖에는 [reserveSpace] 면 광고 높이, [AdSlotFailure.KeepSpace] 에서 이 슬롯이 광고를 보인 적이 있으면([shownBefore])
 *   광고 높이(광고가 있던 자리를 지킨다), 아니면 0
 */
@Suppress("LongParameterList")
internal fun bannerHeightDp(
    adHeightDp: Int,
    reserveSpace: Boolean,
    loaded: Boolean,
    failed: Boolean = false,
    failure: AdSlotFailure = AdSlotFailure.KeepSpace,
    attached: Boolean = true,
    shownBefore: Boolean = loaded,
): Int {
    val height = adHeightDp.coerceAtLeast(0)
    return when {
        attached && loaded -> height
        failure == AdSlotFailure.Collapse && (failed || !attached) -> 0
        reserveSpace -> height
        failure == AdSlotFailure.KeepSpace && shownBefore -> height
        else -> 0
    }
}

/** 배너 실패 뒤 할 일. */
internal sealed class BannerNext {
    /** 다음 대체 단위로 넘어간다. */
    data class Fallback(val index: Int) : BannerNext()

    /** 같은 단위로 다시 요청한다(재시도 판단은 [net.plumt.ads.nextStep]). */
    object Retry : BannerNext()

    /** 한 번 광고가 온 뒤의 갱신 실패 — SDK 가 스스로 다시 갱신하므로 아무것도 하지 않는다. */
    object Nothing : BannerNext()
}

/**
 * [index] 번째 단위(대체 포함 [chainSize] 개)가 실패했을 때. 네트워크 실패([network])면 대체 단위도 같은 이유로
 * 실패하므로 넘어가지 않고 재시도(연결 복구 대기)로 간다.
 */
internal fun bannerNext(index: Int, chainSize: Int, everLoaded: Boolean, network: Boolean = false): BannerNext = when {
    everLoaded -> BannerNext.Nothing
    !network && index + 1 < chainSize -> BannerNext.Fallback(index + 1)
    else -> BannerNext.Retry
}

/**
 * 배너의 자동 갱신을 돌려도 되나. 화면이 전면(RESUMED)이고 연결돼 있을 때만. 오프라인이면 뷰 자체를 없애므로
 * ([BannerSlotController]) 실제로는 전면 여부가 정하고, 연결 조건은 두 상태를 늘 합쳐 판정하려고 둔다.
 */
internal fun bannerShouldRun(resumed: Boolean, online: Boolean): Boolean = resumed && online

/**
 * 배너 하나의 자동 갱신 스위치. 돌릴지([update])가 바뀔 때만 `pause`/`resume` 을 부른다.
 * 요청 직후와 응답(성공·실패) 직후 [reassert] 로 멈춤을 다시 건다.
 *
 * `pause` 는 최선의 노력일 뿐이다 — 광고 SDK 는 요청 전 `pause` 를 무시하고, 요청 뒤에는 SDK 내부 원격 구현에 넘길 뿐이라
 * 갱신 타이머가 멈춘다는 보장이 없다. 그래서 오프라인은 이 스위치가 아니라 뷰 폐기로 막는다([BannerSlotController]).
 */
internal class BannerRunGate {
    private var target: BannerHandle? = null
    private var running: Boolean? = null

    /** 만든 배너를 붙인다. 이미 정해진 상태가 있으면 그대로 건다. */
    fun attach(handle: BannerHandle) {
        target = handle
        when (running) {
            true -> handle.resume()
            false -> handle.pause()
            null -> Unit
        }
    }

    fun update(run: Boolean) {
        if (run == running) return
        running = run
        val handle = target ?: return
        if (run) handle.resume() else handle.pause()
    }

    /** 요청·응답 직후. 멈춰 있어야 하면 다시 멈춘다. */
    fun reassert() {
        if (running == false) target?.pause()
    }
}
