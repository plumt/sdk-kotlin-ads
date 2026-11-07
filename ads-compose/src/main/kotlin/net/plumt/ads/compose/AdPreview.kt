package net.plumt.ads.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.plumt.ads.AdsClient
import net.plumt.ads.inert

/**
 * 광고 미리보기 설정. 켜면 광고 컴포저블이 클라이언트·광고 SDK 없이 실제와 같은 크기·구성의 자리표시 광고를 그린다.
 *
 * @property adsVisible false 면 광고 없음 상태 — 실제와 같이 아무것도 그리지 않는다(종료 팝업은 광고 자리 없이 뜬다)
 */
public class AdPreviewConfig(public val adsVisible: Boolean)

/**
 * 스크린샷 테스트·프리뷰가 광고를 자리표시로 바꾸는 스위치. 기본 null(= 실제 광고).
 * Android Studio 프리뷰(`LocalInspectionMode`)는 이 값 없이도 "광고 있음" 자리표시를 그린다.
 */
public val LocalAdPreview: ProvidableCompositionLocal<AdPreviewConfig?> = compositionLocalOf { null }

/** [content] 안의 광고를 자리표시로 그린다. [adsVisible] 로 광고 있음/없음을 고른다. [ProvideAdsClient] 가 없어도 된다. */
@Composable
public fun AdPreviewMode(adsVisible: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAdPreview provides AdPreviewConfig(adsVisible), content = content)
}

/** 미리보기의 광고 표시 여부. 실제 광고를 쓰는 모드면 null. [LocalAdPreview] 가 프리뷰보다 우선한다. */
@Composable
@ReadOnlyComposable
internal fun adPreviewVisible(): Boolean? = previewVisibility(LocalAdPreview.current, LocalInspectionMode.current)

internal fun previewVisibility(config: AdPreviewConfig?, inspection: Boolean): Boolean? = when {
    config != null -> config.adsVisible
    inspection -> true
    else -> null
}

/** 미리보기에서 클라이언트가 필요한 API(전면형 컨트롤러)에 넘기는, 아무것도 요청하지 않는 클라이언트. */
@Composable
internal fun previewClient(): AdsClient {
    val context = LocalContext.current
    return remember(context) { AdsClient.inert(context) }
}

private val MockGray = Color(0xFFD0D0D4)
private val MockInk = Color(0xFF1F1D18)

/** 카드 템플릿과 같은 구성의 자리표시: 미디어 → 아이콘 + 제목/본문 → CTA. */
@Composable
internal fun MockNativeAdContent(mediaHeight: Dp?, colors: NativeAdColors?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (mediaHeight != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(mediaHeight)
                    .background(MockGray)
                    .testTag(AdTestTags.MOCK_MEDIA),
            )
            Spacer(Modifier.height(NativeAdMetrics.MediaBottomGap))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(NativeAdMetrics.CardIconSize).clip(RoundedCornerShape(10.dp)).background(MockGray))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.net_plumt_ads_preview_headline),
                    color = colors?.headline ?: MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.net_plumt_ads_preview_body),
                    modifier = Modifier.padding(top = 2.dp),
                    color = colors?.body ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(NativeAdMetrics.CardCtaHeight)
                .clip(RoundedCornerShape(22.dp))
                .background(colors?.ctaBackground ?: MockInk),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.net_plumt_ads_preview_cta),
                color = colors?.ctaText ?: Color(0xFFFBF8F2),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 적응형 배너 자리표시. 폭 전체, 높이 [MOCK_BANNER_HEIGHT]. */
@Composable
internal fun MockBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MOCK_BANNER_HEIGHT)
            .background(MockGray)
            .testTag(AdTestTags.MOCK_BANNER),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.net_plumt_ads_preview_banner),
            color = MockInk,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 앵커드 적응형 배너가 폭 411dp 근방에서 갖는 높이(50~60dp 의 중간값). */
internal val MOCK_BANNER_HEIGHT: Dp = 56.dp

/** 자동화 테스트(UI 테스트·Appium)가 찾는 태그. 접근성 설명(contentDescription)과는 따로 둔다. */
public object AdTestTags {
    /** 배너 루트. 광고가 없으면 존재하지 않는다. */
    public const val BANNER: String = "ad_banner"

    /** 목록 카드 루트. */
    public const val NATIVE_LIST_ITEM: String = "ad_native_list_item"

    /** 팝업·바텀시트 안 작은 카드 루트. */
    public const val NATIVE_INLINE: String = "ad_native_inline"

    /** 행 광고 루트. */
    public const val NATIVE_ROW: String = "ad_native_row"

    /** "광고" 배지. 모든 기성 카드가 같은 태그를 쓴다. */
    public const val NATIVE_BADGE: String = "ad_native_badge"

    /** 종료 팝업 루트. */
    public const val EXIT_DIALOG: String = "ad_exit_dialog"

    /** 종료 팝업 안 광고 카드. 광고가 없으면 존재하지 않는다. */
    public const val EXIT_SLOT: String = "ad_exit_slot"

    /** 종료 팝업 취소 버튼. */
    public const val EXIT_CANCEL: String = "ad_exit_cancel"

    /** 종료 팝업 종료 버튼. */
    public const val EXIT_CONFIRM: String = "ad_exit_confirm"

    /** 자리표시 미디어 영역. */
    public const val MOCK_MEDIA: String = "ad_mock_media"

    /** 자리표시 배너. */
    public const val MOCK_BANNER: String = "ad_mock_banner"

    /** 자리표시 행의 아이콘. */
    public const val MOCK_ROW_ICON: String = "ad_mock_row_icon"
}
