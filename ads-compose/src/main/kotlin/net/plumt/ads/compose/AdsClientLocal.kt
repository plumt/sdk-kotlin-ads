package net.plumt.ads.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import net.plumt.ads.AdFrequencyPolicy
import net.plumt.ads.AdUnit
import net.plumt.ads.AdsClient
import net.plumt.ads.FrequencyScope
import net.plumt.ads.FullScreenAdOptions
import net.plumt.ads.FullScreenAdController
import net.plumt.ads.PreloadPolicy

/**
 * 광고 컴포저블이 읽는 클라이언트. [ProvideAdsClient] 로 공급한다. 기본은 null 이다 —
 * 미리보기·스크린샷([AdPreviewMode]·Android Studio 프리뷰)에서는 공급하지 않아도 광고 컴포저블이 자리표시 광고를 그린다.
 * 실제 화면에서 공급하지 않으면 광고 컴포저블이 [IllegalStateException] 을 던진다(배선 누락을 조용히 숨기지 않는다).
 */
public val LocalAdsClient: ProvidableCompositionLocal<AdsClient?> = staticCompositionLocalOf { null }

/** 하위 트리에 [client] 를 공급한다. */
@Composable
public fun ProvideAdsClient(client: AdsClient, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAdsClient provides client, content = content)
}

/** 미리보기가 아닌 곳의 클라이언트. 없으면 배선 누락이다. */
@Composable
internal fun requireAdsClient(): AdsClient = checkNotNull(LocalAdsClient.current) {
    "LocalAdsClient 가 없습니다 — 화면 루트를 ProvideAdsClient 로 감싸세요(미리보기는 AdPreviewMode)"
}

/**
 * 광고를 그릴 수 있는가([AdsClient.adsAvailable]). 목록에 광고 자리를 낼지 정할 때 쓴다(`itemsWithAds(adsEnabled = …)`).
 * 미리보기에서는 [AdPreviewMode] 의 값(프리뷰는 true)이다.
 */
@Composable
public fun rememberAdsAvailable(): Boolean {
    adPreviewVisible()?.let { return it }
    val available by requireAdsClient().adsAvailable.collectAsState()
    return available
}

/**
 * 전면형 컨트롤러를 만들어 컴포지션이 끝나면 destroy 한다. 인자가 바뀌면 새로 만든다.
 * 컨트롤러는 화면 회전·재진입 때 다시 만들어지지만, 빈도 제한은 클라이언트의 공유 정책에 있어 이어진다.
 * 표시 중에 화면이 재생성되면 [FullScreenAdController.show] 에 넘긴 콜백은 더 불리지 않는다 — 보상은 [AdsClient.events] 에서 처리한다.
 * 미리보기에서는 클라이언트 없이 아무것도 불러오지 않는 컨트롤러를 돌려준다(표시하면 `Suppressed`).
 *
 * @param policy null(기본)이면 클라이언트 기본 정책([frequencyScope] 범위). 직접 넘기면 화면 밖(앱 전역)에 둔 인스턴스를 넘긴다 —
 *   컴포지션 안에서 새로 만들면 회전마다 제한이 초기화된다
 * @throws IllegalArgumentException 단위가 전면형이 아니거나 대체 단위의 형식이 다를 때
 */
@Composable
public fun rememberFullScreenAdController(
    unit: AdUnit,
    placement: String = unit.name,
    policy: AdFrequencyPolicy? = null,
    preload: PreloadPolicy = PreloadPolicy.Default,
    fallbacks: List<AdUnit> = emptyList(),
    frequencyScope: FrequencyScope = FrequencyScope.PerFormat,
): FullScreenAdController {
    val client = if (adPreviewVisible() != null) previewClient() else requireAdsClient()
    val controller = remember(client, unit, placement, fallbacks, frequencyScope) {
        val options = FullScreenAdOptions.builder()
            .placement(placement).preload(preload).fallbacks(fallbacks).frequencyScope(frequencyScope)
            .apply { policy?.let(::policy) }
            .build()
        client.fullScreenController(unit, options)
    }
    DisposableEffect(controller) { onDispose { controller.destroy() } }
    return controller
}

/** 컨텍스트 래퍼를 풀어 [Activity] 를 찾는다. 없으면 null. */
public fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
internal fun rememberActivityOrNull(): Activity? = LocalContext.current.findActivity()
