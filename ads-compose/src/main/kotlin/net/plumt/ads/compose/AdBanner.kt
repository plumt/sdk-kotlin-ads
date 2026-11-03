package net.plumt.ads.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.plumt.ads.AdEvent
import net.plumt.ads.AdFormat
import net.plumt.ads.AdsException
import net.plumt.ads.network.BannerHandle
import net.plumt.ads.network.BannerListener

/**
 * 하단 고정용 앵커드 적응형 배너. 화면 폭에 맞춰 크기가 정해진다.
 *
 * - 광고를 쓸 수 없으면(스위치 꺼짐·동의 없음·초기화 전) 그리지 않고 요청도 보내지 않는다
 * - 로드 전과 실패 동안 높이는 0 이다. 도착하면 광고 높이만큼 자리를 차지한다
 * - 생명주기에 맞춰 pause/resume 하고, 컴포지션을 떠나면 destroy 한다
 * - [LocalAdsClient] 가 필요하다
 *
 * @param adUnitId 배너 광고 단위 ID. 개발 중에는 Google 공식 시험용 ID 를 쓴다
 * @param placement 분석 사건에 실리는 배치 이름
 * @throws IllegalArgumentException [adUnitId] 가 비었을 때
 */
@Composable
public fun AdBanner(
    adUnitId: String,
    modifier: Modifier = Modifier,
    placement: String = "banner",
) {
    require(adUnitId.isNotBlank()) { "adUnitId 가 비었습니다: placement=$placement" }
    val client = LocalAdsClient.current
    val available by client.adsAvailable.collectAsState()
    if (!available) return

    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    var loaded by remember(adUnitId, widthDp) { mutableStateOf(false) }

    val handle: BannerHandle? = remember(client, adUnitId, widthDp) {
        val events = client.events
        try {
            client.network.banner.create(context, adUnitId, widthDp, object : BannerListener {
                override fun onLoaded() {
                    loaded = true
                    events.onEvent(AdEvent.Loaded(AdFormat.Banner, placement))
                }

                override fun onLoadFailed(error: AdsException) {
                    loaded = false
                    events.onEvent(AdEvent.LoadFailed(AdFormat.Banner, placement, error.code, error.message.orEmpty()))
                }

                override fun onImpression() = events.onEvent(AdEvent.Impression(AdFormat.Banner, placement))
                override fun onClicked() = events.onEvent(AdEvent.Clicked(AdFormat.Banner, placement))
            })
        } catch (e: AdsException) {
            events.onEvent(AdEvent.LoadFailed(AdFormat.Banner, placement, e.code, e.message.orEmpty()))
            null
        }
    }
    if (handle == null) return

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(handle, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> handle.pause()
                Lifecycle.Event.ON_RESUME -> handle.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(handle) {
        client.events.onEvent(AdEvent.Requested(AdFormat.Banner, placement))
        handle.load()
        onDispose { handle.destroy() }
    }

    // 폭이 바뀌면(회전·폴드·분할) 새 배너가 만들어지고 이전 것은 destroy 된다 — 뷰도 새로 붙인다
    key(handle) {
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .height(if (loaded) handle.heightDp.dp else 0.dp),
            factory = { handle.view },
        )
    }
}
