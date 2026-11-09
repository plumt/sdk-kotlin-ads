package net.plumt.ads.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.plumt.ads.AdUnit
import net.plumt.ads.PrefetchedNativeAd
import net.plumt.ads.network.NativeAdHandle

/** 종료 팝업이 쓰는 배치 이름 기본값. */
public const val EXIT_AD_PLACEMENT: String = "exit"

/**
 * 종료 팝업에 쓸 네이티브 광고를 앱 수명 동안 한 장 미리 받아 두고 돌려준다. 팝업을 띄우는 화면(항상 컴포지션에 있는 곳)에서 부른다.
 * 화면에 다시 들어와도 새로 요청하지 않고, 팝업이 닫혀 광고를 쓰면 다음 것을 받는다. 받은 지 [ttlMillis] 가 지난 것은 쓰지 않는다.
 * 숨김·설정 오류·미리보기면 요청 없이 null 이다.
 */
@Composable
public fun rememberExitAd(
    unit: AdUnit,
    placement: String = EXIT_AD_PLACEMENT,
    ttlMillis: Long = PrefetchedNativeAd.DEFAULT_TTL_MILLIS,
    fallbacks: List<AdUnit> = emptyList(),
): NativeAdHandle? {
    if (adPreviewVisible() != null) return null
    val prefetched = rememberPrefetched(unit, placement, ttlMillis, fallbacks)
    val held by prefetched.ad.collectAsState()
    return held?.takeIf { prefetched.isFresh(it) }
}

@Composable
private fun rememberPrefetched(unit: AdUnit, placement: String, ttlMillis: Long, fallbacks: List<AdUnit>): PrefetchedNativeAd {
    val client = requireAdsClient()
    val prefetched = remember(client, unit, placement, ttlMillis, fallbacks) {
        client.prefetchedNativeAd(unit, placement, ttlMillis, fallbacks)
    }
    LaunchedEffect(prefetched) { prefetched.prefetch() }
    return prefetched
}

/**
 * 종료 확인 팝업: 제목, 메시지, 가운데 네이티브 광고, 취소/종료 버튼. 광고는 [rememberExitAd] 와 같은 앱 수명 캐시를 쓰고,
 * 팝업이 닫히면 쓴 광고를 버리고 다음 것을 받는다. 광고가 없으면 광고 자리 없이 팝업만 뜬다.
 * 광고와 버튼은 [AdExitDialogDefaults.AdButtonGap] 이상 떨어뜨려 광고를 누르려다 종료를 누르는 오클릭을 막는다.
 *
 * @param onDismiss 취소 버튼, 팝업 밖 터치, 뒤로 가기
 * @param onExit 종료 버튼
 */
@Composable
public fun ExitConfirmDialog(
    unit: AdUnit,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.ads_exit_title),
    message: String = stringResource(R.string.ads_exit_message),
    cancelLabel: String = stringResource(R.string.ads_exit_cancel),
    confirmLabel: String = stringResource(R.string.ads_exit_confirm),
    colors: NativeAdColors = NativeAdDefaults.colors(),
    placement: String = EXIT_AD_PLACEMENT,
) {
    val ad = if (adPreviewVisible() != null) {
        null
    } else {
        val prefetched = rememberPrefetched(unit, placement, PrefetchedNativeAd.DEFAULT_TTL_MILLIS, emptyList())
        val held by prefetched.ad.collectAsState()
        val fresh = held?.takeIf { prefetched.isFresh(it) }
        // 팝업이 사라질 때 보인 광고를 돌려주고 다음 것을 받는다
        DisposableEffect(fresh) { onDispose { fresh?.let(prefetched::consume) } }
        fresh
    }
    ExitConfirmDialog(ad, onDismiss, onExit, modifier, title, message, cancelLabel, confirmLabel, colors)
}

/** 미리 받아 둔 [ad] 로 그리는 종료 확인 팝업. null 이면 광고 자리 없이 팝업만 뜬다. 광고 수명은 호출부가 맡는다. */
@Composable
public fun ExitConfirmDialog(
    ad: NativeAdHandle?,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.ads_exit_title),
    message: String = stringResource(R.string.ads_exit_message),
    cancelLabel: String = stringResource(R.string.ads_exit_cancel),
    confirmLabel: String = stringResource(R.string.ads_exit_confirm),
    colors: NativeAdColors = NativeAdDefaults.colors(),
) {
    val preview = adPreviewVisible()
    val media = NativeAdDefaults.DialogMediaHeight
    val adContent: (@Composable () -> Unit)? = when {
        preview == true -> {
            { MockNativeAdContent(media, colors, Modifier.padding(top = NativeAdDefaults.AdChoicesInset)) }
        }
        preview == false -> null
        ad != null && canDrawNative(ad.assets.hasVideo, media) -> {
            { NativeAdContent(ad, colors = colors, mediaHeight = media, adChoicesTopInset = NativeAdDefaults.AdChoicesInset) }
        }
        else -> null
    }
    Dialog(onDismissRequest = onDismiss) {
        ExitConfirmPanel(title, message, cancelLabel, confirmLabel, onDismiss, onExit, modifier, adContent)
    }
}

/** 종료 팝업 치수. */
public object AdExitDialogDefaults {
    /** 광고와 버튼 사이 최소 간격. */
    public val AdButtonGap: Dp = 32.dp
}

@Composable
private fun ExitConfirmPanel(
    title: String,
    message: String,
    cancelLabel: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier,
    ad: (@Composable () -> Unit)?,
) {
    Surface(
        modifier = modifier.testTag(AdTestTags.EXIT_DIALOG),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(16.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ad != null) {
                Spacer(Modifier.height(20.dp))
                NativeAdFrame(tag = AdTestTags.EXIT_SLOT, container = MaterialTheme.colorScheme.surface, padding = 12.dp, content = ad)
            }
            Spacer(Modifier.height(if (ad != null) AdExitDialogDefaults.AdButtonGap else 24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(AdTestTags.EXIT_CANCEL)) { Text(cancelLabel) }
                TextButton(onClick = onExit, modifier = Modifier.testTag(AdTestTags.EXIT_CONFIRM)) { Text(confirmLabel) }
            }
        }
    }
}
