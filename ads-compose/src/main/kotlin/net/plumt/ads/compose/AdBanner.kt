package net.plumt.ads.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.plumt.ads.AdErrorReason
import net.plumt.ads.AdEvent
import net.plumt.ads.AdFormat
import net.plumt.ads.AdUnit
import net.plumt.ads.AdsClient
import net.plumt.ads.AdsException
import net.plumt.ads.PreloadPolicy
import net.plumt.ads.awaitRetry
import net.plumt.ads.classifyOffline
import net.plumt.ads.network.BannerHandle
import net.plumt.ads.network.BannerListener

/**
 * 하단 고정용 앵커드 적응형 배너. 부모가 준 실제 폭에 맞춰 크기가 정해진다(패딩·좁은 레이아웃 안에서도 자리를 넘지 않는다).
 *
 * - 광고를 쓸 수 없으면(스위치 꺼짐·동의 없음·초기화 전) 그리지 않고 요청도 보내지 않는다
 * - 단위가 모드와 맞지 않으면(운영 모드의 빈 ID·시험 ID) 던지지 않고 그리지 않는다. 원인은 `AdsClient.configurationErrors`
 * - [reserveSpace] 가 true(기본)면 광고가 오기 전부터 광고 높이만큼 자리를 잡는다 — 광고 도착 때 콘텐츠가 밀려 실수 클릭이 나는 것을 막는다
 * - 첫 광고 전에 실패하면 [fallbacks] 의 다음 단위로 넘어가고, 다 쓰면 [retry] 의 분류별 규칙으로 다시 요청한다.
 *   그동안 자리는 [failure] 를 따른다(기본 유지). 실패마다 [onLoadFailed] 가 불린다
 * - 한 번 광고가 오면 (연결된 동안) 자동 갱신이 실패해도 높이를 유지한다(SDK 가 이전 광고를 계속 보인다)
 * - 생명주기에 맞춰 pause/resume 하고, 컴포지션을 떠나면 destroy 한다
 * - 오프라인이 되면 배너 뷰를 destroy 하고 슬롯에서 뗀다(재요청·자동 갱신 없음). 자리는 [failure] 를 따른다 —
 *   `Collapse` 면 접고, `KeepSpace` 면 광고 높이(광고를 보였거나 [reserveSpace] 일 때)를 빈 자리로 둔다.
 *   연결이 돌아오면 새 뷰를 한 번 만들어 요청한다. 오프라인으로 시작하면 연결될 때까지 뷰를 만들지 않는다
 * - 미리보기에서는 56dp 자리표시를 그린다
 *
 * @param unit 배너 단위
 * @param placement 분석 사건에 실리는 배치 이름. 기본은 단위 이름
 * @throws IllegalArgumentException 단위나 대체 단위가 배너가 아닐 때
 */
@Composable
public fun AdBanner(
    unit: AdUnit,
    modifier: Modifier = Modifier,
    placement: String = unit.name,
    reserveSpace: Boolean = true,
    failure: AdSlotFailure = AdSlotFailure.KeepSpace,
    fallbacks: List<AdUnit> = emptyList(),
    retry: PreloadPolicy = PreloadPolicy.Default,
    onLoadFailed: (AdsException) -> Unit = {},
) {
    unit.requireFormat(AdFormat.Banner)
    fallbacks.forEach { it.requireFormat(AdFormat.Banner) }
    val preview = adPreviewVisible()
    if (preview != null) {
        if (preview) MockBanner(modifier.testTag(AdTestTags.BANNER))
        return
    }
    val client = requireAdsClient()
    val available by client.adsAvailable.collectAsState()
    val ids = remember(client, unit, fallbacks, placement) {
        (listOf(unit) + fallbacks).distinct().mapNotNull { client.adUnitId(it, placement) }
    }
    if (!available || ids.isEmpty()) return

    BoxWithConstraints(modifier.fillMaxWidth().testTag(AdTestTags.BANNER)) {
        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        val widthDp = bannerWidthDp(maxWidth.value, constraints.hasBoundedWidth, screenWidthDp) ?: return@BoxWithConstraints
        BannerSlot(client, ids, placement, widthDp, reserveSpace, failure, retry, onLoadFailed)
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun BannerSlot(
    client: AdsClient,
    ids: List<String>,
    placement: String,
    widthDp: Int,
    reserveSpace: Boolean,
    failure: AdSlotFailure,
    retry: PreloadPolicy,
    onLoadFailed: (AdsException) -> Unit,
) {
    val context = LocalContext.current
    val failedCallback by rememberUpdatedState(onLoadFailed)
    var index by remember(ids, widthDp) { mutableIntStateOf(0) }
    // 이 슬롯이 광고를 보인 적이 있나(오프라인으로 뷰를 떼도 KeepSpace 면 그 자리를 지킨다)
    var shownBefore by remember(ids, widthDp) { mutableStateOf(false) }
    // 마지막으로 붙었던 뷰의 광고 높이. 오프라인으로 뷰를 뗀 동안 자리 높이로 쓴다
    var lastHeightDp by remember(ids, widthDp) { mutableIntStateOf(0) }
    val adUnitId = ids[index.coerceIn(0, ids.lastIndex)]
    val online = remember(client) { client.connectivity.online() }

    // 붙은 뷰마다의 상태. 뷰가 바뀌면(오프라인 뒤 재생성) 새로 시작한다
    var handle by remember(client, adUnitId, widthDp) { mutableStateOf<BannerHandle?>(null) }
    var loaded by remember(client, adUnitId, widthDp) { mutableStateOf(false) }
    var lastError by remember(client, adUnitId, widthDp) { mutableStateOf<AdsException?>(null) }
    var retryError by remember(client, adUnitId, widthDp) { mutableStateOf<AdsException?>(null) }
    var failures by remember(client, adUnitId, widthDp) { mutableIntStateOf(0) }

    val controller = remember(client, adUnitId, widthDp) {
        val events = client.events
        lateinit var slot: BannerSlotController
        slot = BannerSlotController(
            create = {
                try {
                    client.network.banner.create(context, adUnitId, widthDp, object : BannerListener {
                        override fun onLoaded() {
                            slot.reassert()
                            loaded = true
                            shownBefore = true
                            lastError = null
                            retryError = null
                            events.onEvent(AdEvent.Loaded(AdFormat.Banner, placement))
                        }

                        override fun onLoadFailed(error: AdsException) {
                            slot.reassert()
                            // 오프라인이면 SDK 가 내부 오류로 알려도 네트워크 실패다 — 재시도가 연결 복구를 기다린다
                            val failure = error.classifyOffline(slot.online != false)
                            events.onEvent(
                                AdEvent.LoadFailed(AdFormat.Banner, placement, failure.code, failure.message.orEmpty(), failure.reason),
                            )
                            failedCallback(failure)
                            when (val next = bannerNext(index, ids.size, loaded, failure.reason == AdErrorReason.Network)) {
                                is BannerNext.Fallback -> index = next.index
                                BannerNext.Retry -> {
                                    lastError = failure
                                    retryError = failure
                                    failures++
                                }
                                BannerNext.Nothing -> Unit
                            }
                        }

                        override fun onImpression() = events.onEvent(AdEvent.Impression(AdFormat.Banner, placement))
                        override fun onClicked() = events.onEvent(AdEvent.Clicked(AdFormat.Banner, placement))
                    })
                } catch (e: AdsException) {
                    events.onEvent(AdEvent.LoadFailed(AdFormat.Banner, placement, e.code, e.message.orEmpty(), e.reason))
                    failedCallback(e)
                    null
                }
            },
            onRequested = { events.onEvent(AdEvent.Requested(AdFormat.Banner, placement)) },
            onHandle = { attached ->
                // 새 뷰는 아직 광고가 없다. 이전 뷰의 재시도는 넘기지 않는다(새 뷰가 이미 한 번 요청한다)
                handle = attached
                loaded = false
                retryError = null
                if (attached != null) lastHeightDp = attached.heightDp
            },
        )
        slot
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // 자동 갱신은 화면이 전면일 때만 돌린다. 오프라인은 뷰를 없애므로 수명주기 resume 이 덮을 수 없다
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_RESUME) {
                controller.setResumed(event == Lifecycle.Event.ON_RESUME)
            }
        }
        controller.setResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // 연결돼 있을 때만 뷰가 있다 — 오프라인이 되면 destroy 하고 떼며, 돌아오면 새로 만들어 한 번 요청한다
    LaunchedEffect(controller, online) {
        online.collect { controller.setOnline(it) }
    }
    // 첫 광고 전 마지막 단위까지 실패: 분류별로 기다렸다 같은 뷰로 다시 요청한다(재시도 불가 분류면 멈춘다)
    LaunchedEffect(handle, failures) {
        val error = retryError ?: return@LaunchedEffect
        if (handle == null) return@LaunchedEffect
        // 오프라인 동안은 다시 요청하지 않는다 — 대기 뒤에도 연결이 돌아올 때까지 기다린다
        if (!retry.awaitRetry(error.reason, failures - 1, online)) return@LaunchedEffect
        retryError = null
        controller.loadAgain()
    }

    val attached = handle
    val heightDp = bannerHeightDp(
        adHeightDp = attached?.heightDp ?: lastHeightDp,
        reserveSpace = reserveSpace,
        loaded = loaded,
        failed = lastError != null,
        failure = failure,
        attached = attached != null,
        shownBefore = shownBefore,
    )
    if (attached == null) {
        // 오프라인(또는 아직 연결 상태를 모름): 뷰 없이 정책대로 자리만 둔다
        if (heightDp > 0) Box(Modifier.fillMaxWidth().height(heightDp.dp))
        return
    }
    // 폭이 바뀌거나 오프라인 뒤 다시 만들면 새 뷰가 오고 이전 것은 destroy 된다 — 뷰도 새로 붙인다
    key(attached) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp),
            factory = { attached.view },
        )
    }
}
