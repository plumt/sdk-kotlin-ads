package net.plumt.ads.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import net.plumt.ads.AdFormat
import net.plumt.ads.AdFrequencyPolicy
import net.plumt.ads.AdsClient
import net.plumt.ads.FullScreenAdController
import net.plumt.ads.PreloadPolicy

/** 광고 컴포저블이 읽는 클라이언트. [ProvideAdsClient] 로 공급한다. */
public val LocalAdsClient: androidx.compose.runtime.ProvidableCompositionLocal<AdsClient> =
    staticCompositionLocalOf { error("LocalAdsClient 가 없습니다 — 화면 루트를 ProvideAdsClient 로 감싸세요") }

/** 하위 트리에 [client] 를 공급한다. */
@Composable
public fun ProvideAdsClient(client: AdsClient, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAdsClient provides client, content = content)
}

/**
 * 전면형 컨트롤러를 만들어 컴포지션이 끝나면 destroy 한다. 인자가 바뀌면 새로 만든다.
 *
 * @param policy 기본은 [AdsClient.defaultPolicyFor]
 */
@Composable
public fun rememberFullScreenAdController(
    format: AdFormat,
    placement: String,
    adUnitId: String,
    policy: AdFrequencyPolicy? = null,
    preload: PreloadPolicy = PreloadPolicy(),
): FullScreenAdController {
    val client = LocalAdsClient.current
    val controller = remember(client, format, placement, adUnitId) {
        client.fullScreenController(format, placement, adUnitId, policy ?: AdsClient.defaultPolicyFor(format), preload)
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
