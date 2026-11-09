package net.plumt.ads.compose

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import net.plumt.ads.AdUnit
import net.plumt.ads.AdsException
import net.plumt.ads.network.NativeAdHandle
import net.plumt.ads.network.NativeAdViews

/**
 * 기성 네이티브 카드의 글자·CTA 색. 카드 내용은 뷰로 그려 Compose 테마를 자동으로 따르지 않으므로
 * [NativeAdDefaults.colors] 또는 앱 디자인 시스템 색으로 만들어 넘긴다(다크 테마 대응).
 */
@Immutable
public class NativeAdColors(
    public val headline: Color,
    public val body: Color,
    public val ctaBackground: Color,
    public val ctaText: Color,
)

/** 기성 네이티브 카드 기본값. */
public object NativeAdDefaults {
    /** `MaterialTheme` 의 현재(라이트·다크) 색을 따르는 기본값. */
    @Composable
    public fun colors(
        headline: Color = MaterialTheme.colorScheme.onSurface,
        body: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        ctaBackground: Color = MaterialTheme.colorScheme.primary,
        ctaText: Color = MaterialTheme.colorScheme.onPrimary,
    ): NativeAdColors = NativeAdColors(headline, body, ctaBackground, ctaText)

    /** 목록 카드 미디어 높이. */
    public val ListMediaHeight: Dp = 180.dp

    /** 팝업·바텀시트 카드 미디어 높이(미디어 뷰 최소 120dp). */
    public val InlineMediaHeight: Dp = 120.dp

    /** 종료 팝업 카드 미디어 높이. */
    public val DialogMediaHeight: Dp = 140.dp

    /** AdChoices 아이콘(우상단)이 본문과 겹치지 않게 비우는 위쪽 여백. */
    public val AdChoicesInset: Dp = 16.dp
}

/** 템플릿·자리표시가 함께 쓰는 고정 치수. */
internal object NativeAdMetrics {
    val MediaBottomGap = 12.dp
    val CardIconSize = 44.dp
    val CardCtaHeight = 40.dp
    val CardCtaRadius = 22.dp
    val IconGap = 12.dp
    val CtaGap = 8.dp
    val BadgeGap = 6.dp
    val BodyTopGap = 2.dp
    val RowCtaHeight = 32.dp
    val CtaHorizontalPadding = 12.dp
    val CtaTextSize = 12.sp
    val BadgeTextSize = 10.sp
    val BadgeHorizontalPadding = 5.dp
    val BadgeVerticalPadding = 1.dp
    val BadgeCornerRadius = 4.dp
}

/**
 * 불러온 광고를 라이브러리 템플릿(미디어 → 아이콘 + 제목/본문 → CTA)으로 그린다. 배경은 투명하고
 * 카드 틀·"광고" 배지는 그리지 않는다 — 배지까지 필요하면 [NativeAdListItem]·[NativeAdInline]·[ExitConfirmDialog] 를 쓴다.
 * 동영상 광고인데 [mediaHeight] 가 없거나 120dp 미만이면 그리지 않는다.
 *
 * @param colors 글자·CTA 색. null 이면 템플릿 기본(라이트) 색
 * @param mediaHeight 미디어 높이. null 이면 미디어를 그리지 않는다. 쓸 때는 120dp 이상
 * @param adChoicesTopInset 위쪽에 비울 높이
 */
@Composable
public fun NativeAdContent(
    ad: NativeAdHandle,
    modifier: Modifier = Modifier,
    colors: NativeAdColors? = null,
    mediaHeight: Dp? = null,
    adChoicesTopInset: Dp = 0.dp,
) {
    if (!canDrawNative(ad.assets.hasVideo, mediaHeight)) return
    val client = requireAdsClient()
    val density = LocalDensity.current
    val mediaPx = mediaHeight?.let { with(density) { it.roundToPx() } }
    val insetPx = with(density) { adChoicesTopInset.roundToPx() }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx -> FrameLayout(ctx) },
        update = { container ->
            if (container.getTag(TAG_BOUND) === ad) return@AndroidView
            container.removeAllViews()
            val root = LayoutInflater.from(container.context).inflate(R.layout.ads_native_card, container, true)
            val views = cardViews(root, container.context, mediaPx) { client.network.native.createMediaView(it) }
            root.findViewById<View>(R.id.ads_card_content).let { it.setPadding(it.paddingLeft, insetPx, it.paddingRight, it.paddingBottom) }
            if (ad.bind(container, views)) {
                colors?.let { applyCardColors(views, it, density.density) }
                container.setTag(TAG_BOUND, ad)
            } else {
                container.removeAllViews()
            }
        },
    )
}

private fun cardViews(root: View, context: Context, mediaPx: Int?, createMedia: (Context) -> View?): NativeAdViews {
    val mediaSlot = root.findViewById<FrameLayout>(R.id.ads_card_media)
    val media = mediaPx?.let { createMedia(context) }?.also { view ->
        mediaSlot.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mediaPx))
        mediaSlot.visibility = View.VISIBLE
    }
    return NativeAdViews.builder()
        .headline(root.findViewById(R.id.ads_card_headline))
        .body(root.findViewById(R.id.ads_card_body))
        .callToAction(root.findViewById(R.id.ads_card_cta))
        .icon(root.findViewById(R.id.ads_card_icon))
        .media(media)
        .build()
}

private fun applyCardColors(views: NativeAdViews, colors: NativeAdColors, density: Float) {
    (views.headline as? TextView)?.setTextColor(colors.headline.toArgb())
    (views.body as? TextView)?.setTextColor(colors.body.toArgb())
    (views.callToAction as? TextView)?.apply {
        setTextColor(colors.ctaText.toArgb())
        background = roundedBackground(colors.ctaBackground, NativeAdMetrics.CardCtaRadius.value * density)
    }
}

private fun roundedBackground(color: Color, radiusPx: Float) = GradientDrawable().apply {
    setColor(color.toArgb())
    cornerRadius = radiusPx
}

/** 카드 틀: 윤곽선·배경·"광고" 배지. 배지는 항상 그린다(광고 표시 정책). */
@Composable
internal fun NativeAdFrame(
    tag: String,
    container: Color,
    padding: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(12.dp),
        color = container,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(padding)) {
            NativeAdBadge()
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun NativeAdBadge() {
    Surface(
        modifier = Modifier.testTag(AdTestTags.NATIVE_BADGE),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = stringResource(R.string.ads_native_badge),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 미리보기·광고 있음/없음에 따라 카드 하나를 그린다. */
@Composable
private fun NativeCard(
    ad: NativeAdHandle?,
    tag: String,
    container: Color,
    padding: Dp,
    mediaHeight: Dp,
    colors: NativeAdColors,
    modifier: Modifier,
) {
    val preview = adPreviewVisible()
    if (preview != null) {
        if (preview) {
            NativeAdFrame(tag, container, padding, modifier) {
                MockNativeAdContent(mediaHeight, colors, Modifier.padding(top = NativeAdDefaults.AdChoicesInset))
            }
        }
        return
    }
    if (ad == null || !canDrawNative(ad.assets.hasVideo, mediaHeight)) return
    NativeAdFrame(tag, container, padding, modifier) {
        NativeAdContent(ad, colors = colors, mediaHeight = mediaHeight, adChoicesTopInset = NativeAdDefaults.AdChoicesInset)
    }
}

/**
 * 목록 사이에 끼우는 네이티브 카드(큰 미디어 포함). 광고가 없으면(숨김·불러오는 중·실패) 카드 자체를 그리지 않는다.
 * "광고" 배지는 항상 그린다. 목록 항목 사이에는 호출부가 일반 항목과 같은 간격 이상을 둔다.
 */
@Composable
public fun NativeAdListItem(
    unit: AdUnit,
    modifier: Modifier = Modifier,
    colors: NativeAdColors = NativeAdDefaults.colors(),
    onLoadFailed: (AdsException) -> Unit = {},
) {
    val ad = rememberNativeAd(unit, onLoadFailed = onLoadFailed)
    NativeCard(ad, AdTestTags.NATIVE_LIST_ITEM, MaterialTheme.colorScheme.surface, 16.dp, NativeAdDefaults.ListMediaHeight, colors, modifier)
}

/** [pool] 의 [slot] 광고로 그리는 목록 카드. 같은 화면에 여러 장이면 이것을 쓴다. */
@Composable
public fun NativeAdListItem(
    pool: NativeAdPool,
    slot: Int,
    modifier: Modifier = Modifier,
    colors: NativeAdColors = NativeAdDefaults.colors(),
) {
    NativeCard(pool[slot], AdTestTags.NATIVE_LIST_ITEM, MaterialTheme.colorScheme.surface, 16.dp, NativeAdDefaults.ListMediaHeight, colors, modifier)
}

/**
 * 팝업·바텀시트 안에 넣는 작은 네이티브 카드. 미디어는 정책 최소(120dp)로 그린다. 광고가 없으면 아무것도 그리지 않는다.
 * 바텀시트의 버튼·입력과 떨어뜨려 둔다.
 */
@Composable
public fun NativeAdInline(
    unit: AdUnit,
    modifier: Modifier = Modifier,
    colors: NativeAdColors = NativeAdDefaults.colors(),
    onLoadFailed: (AdsException) -> Unit = {},
) {
    val ad = rememberNativeAd(unit, onLoadFailed = onLoadFailed)
    NativeCard(ad, AdTestTags.NATIVE_INLINE, MaterialTheme.colorScheme.surfaceVariant, 12.dp, NativeAdDefaults.InlineMediaHeight, colors, modifier)
}

/** [pool] 의 [slot] 광고로 그리는 작은 카드. */
@Composable
public fun NativeAdInline(
    pool: NativeAdPool,
    slot: Int,
    modifier: Modifier = Modifier,
    colors: NativeAdColors = NativeAdDefaults.colors(),
) {
    NativeCard(pool[slot], AdTestTags.NATIVE_INLINE, MaterialTheme.colorScheme.surfaceVariant, 12.dp, NativeAdDefaults.InlineMediaHeight, colors, modifier)
}

// ============ 행 광고 ============

/**
 * [NativeAdRow] 의 겉모습. 앱 목록 항목과 같은 값을 넘겨 주변과 어울리게 한다. [NativeAdRowDefaults.style] 로 만든다(바꿀 값만 넘긴다).
 * 글자 크기는 sp 여야 한다(뷰로 그린다).
 */
@Immutable
public class NativeAdRowStyle internal constructor(
    /** 행 최소 높이. AdChoices(우상단 약 16dp)와 CTA 가 겹치지 않으려면 64dp 근처 이상. */
    public val minHeight: Dp,

    /** 행 안쪽 여백. */
    public val contentPadding: PaddingValues,

    /** 아이콘 크기. */
    public val iconSize: Dp,

    /** 아이콘 모서리 반경. */
    public val iconCornerRadius: Dp,

    /** 행 배경. */
    public val background: Color,

    /** 행 모서리 반경. */
    public val cornerRadius: Dp,

    /** 윤곽선 색. null 이면 윤곽선 없음. */
    public val borderColor: Color?,

    /** 제목 색. */
    public val titleColor: Color,

    /** 제목 글자 크기(sp). */
    public val titleSize: TextUnit,

    /** 본문 색. */
    public val bodyColor: Color,

    /** 본문 글자 크기(sp). */
    public val bodySize: TextUnit,

    /** CTA 칩 배경. */
    public val ctaBackground: Color,

    /** CTA 칩 글자 색. */
    public val ctaContentColor: Color,

    /** CTA 칩 모서리 반경. */
    public val ctaCornerRadius: Dp,

    /** "광고" 배지 배경. */
    public val badgeBackground: Color,

    /** "광고" 배지 글자 색. */
    public val badgeContentColor: Color,

    /** 위 구분선을 그린다. */
    public val showDividerTop: Boolean,

    /** 아래 구분선을 그린다. */
    public val showDividerBottom: Boolean,

    /** 구분선 색. */
    public val dividerColor: Color,
) {
    private fun parts(): List<Any?> = listOf(
        minHeight, contentPadding, iconSize, iconCornerRadius, background, cornerRadius, borderColor,
        titleColor, titleSize, bodyColor, bodySize, ctaBackground, ctaContentColor, ctaCornerRadius,
        badgeBackground, badgeContentColor, showDividerTop, showDividerBottom, dividerColor,
    )

    override fun equals(other: Any?): Boolean = other is NativeAdRowStyle && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = "NativeAdRowStyle(" + PART_NAMES.zip(parts()).joinToString { (n, v) -> "$n=$v" } + ")"

    private companion object {
        val PART_NAMES = listOf(
            "minHeight", "contentPadding", "iconSize", "iconCornerRadius", "background", "cornerRadius", "borderColor",
            "titleColor", "titleSize", "bodyColor", "bodySize", "ctaBackground", "ctaContentColor", "ctaCornerRadius",
            "badgeBackground", "badgeContentColor", "showDividerTop", "showDividerBottom", "dividerColor",
        )
    }
}

/** [NativeAdRowStyle] 기본값. */
public object NativeAdRowDefaults {
    /** `MaterialTheme` 토큰 기반 기본 스타일. 바꿀 값만 넘긴다. */
    @Composable
    public fun style(
        minHeight: Dp = 64.dp,
        contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        iconSize: Dp = 40.dp,
        iconCornerRadius: Dp = 8.dp,
        background: Color = MaterialTheme.colorScheme.surface,
        cornerRadius: Dp = 0.dp,
        borderColor: Color? = null,
        titleColor: Color = MaterialTheme.colorScheme.onSurface,
        titleSize: TextUnit = 15.sp,
        bodyColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        bodySize: TextUnit = 13.sp,
        ctaBackground: Color = MaterialTheme.colorScheme.primary,
        ctaContentColor: Color = MaterialTheme.colorScheme.onPrimary,
        ctaCornerRadius: Dp = 16.dp,
        badgeBackground: Color = MaterialTheme.colorScheme.secondaryContainer,
        badgeContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
        showDividerTop: Boolean = false,
        showDividerBottom: Boolean = false,
        dividerColor: Color = MaterialTheme.colorScheme.outlineVariant,
    ): NativeAdRowStyle = NativeAdRowStyle(
        minHeight, contentPadding, iconSize, iconCornerRadius, background, cornerRadius, borderColor,
        titleColor, titleSize, bodyColor, bodySize, ctaBackground, ctaContentColor, ctaCornerRadius,
        badgeBackground, badgeContentColor, showDividerTop, showDividerBottom, dividerColor,
    )
}

/**
 * 목록 한 줄처럼 보이는 네이티브 광고: [아이콘] [배지 + 제목 / 본문] [CTA 칩]. 큰 미디어는 그리지 않아 동영상 광고는 그리지 않는다 —
 * 이 단위는 콘솔에서 이미지 전용으로 둔다. 광고가 없으면 높이 0 이고, [reserveSpaceWhileLoading] 이면 불러오는 동안
 * `style.minHeight` 를 비워 두며, [failure] 가 [AdSlotFailure.KeepSpace] 면 실패 뒤에도 그 자리를 둔다.
 */
@Composable
public fun NativeAdRow(
    unit: AdUnit,
    modifier: Modifier = Modifier,
    style: NativeAdRowStyle = NativeAdRowDefaults.style(),
    reserveSpaceWhileLoading: Boolean = false,
    failure: AdSlotFailure = AdSlotFailure.Collapse,
    onLoadFailed: (AdsException) -> Unit = {},
) {
    val pool = rememberNativeAdPool(unit, 1, onLoadFailed = onLoadFailed)
    NativeAdRow(pool, 0, modifier, style, reserveSpaceWhileLoading, failure)
}

/** [pool] 의 [slot] 광고로 그리는 행. 같은 슬롯은 같은 광고를 유지한다. */
@Composable
public fun NativeAdRow(
    pool: NativeAdPool,
    slot: Int,
    modifier: Modifier = Modifier,
    style: NativeAdRowStyle = NativeAdRowDefaults.style(),
    reserveSpaceWhileLoading: Boolean = false,
    failure: AdSlotFailure = AdSlotFailure.Collapse,
) {
    val preview = adPreviewVisible()
    if (preview != null) {
        if (preview) NativeAdRowFrame(modifier, style) { MockNativeAdRowContent(style) }
        return
    }
    val ad = pool[slot]?.takeIf { canDrawNative(it.assets.hasVideo, null) }
    when (slotDraw(ad != null, pool.state, failure, reserveSpaceWhileLoading)) {
        SlotDraw.Ad -> NativeAdRowFrame(modifier, style) { NativeAdRowContent(ad!!, style) }
        SlotDraw.Placeholder -> Spacer(modifier.fillMaxWidth().height(style.minHeight))
        SlotDraw.Nothing -> Unit
    }
}

@Composable
private fun NativeAdRowFrame(modifier: Modifier, style: NativeAdRowStyle, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(style.cornerRadius)
    Column(modifier = modifier.fillMaxWidth().testTag(AdTestTags.NATIVE_ROW)) {
        if (style.showDividerTop) HorizontalDivider(color = style.dividerColor)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(style.background)
                .then(style.borderColor?.let { Modifier.border(1.dp, it, shape) } ?: Modifier),
        ) { content() }
        if (style.showDividerBottom) HorizontalDivider(color = style.dividerColor)
    }
}

@Composable
private fun NativeAdRowContent(ad: NativeAdHandle, style: NativeAdRowStyle) {
    val density = LocalDensity.current.density
    val direction = LocalLayoutDirection.current
    val badge = stringResource(R.string.ads_native_badge)
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx -> FrameLayout(ctx) },
        update = { container ->
            if (container.getTag(TAG_BOUND) === ad) return@AndroidView
            container.removeAllViews()
            val root = LayoutInflater.from(container.context).inflate(R.layout.ads_native_row, container, true)
            val views = NativeAdViews.builder()
                .headline(root.findViewById(R.id.ads_row_headline))
                .body(root.findViewById(R.id.ads_row_body))
                .callToAction(root.findViewById(R.id.ads_row_cta))
                .icon(root.findViewById(R.id.ads_row_icon))
                .build()
            if (ad.bind(container, views)) {
                styleRow(root, style, density, direction, badge)
                container.setTag(TAG_BOUND, ad)
            } else {
                container.removeAllViews()
            }
        },
    )
}

private fun styleRow(root: View, style: NativeAdRowStyle, density: Float, direction: LayoutDirection, badgeText: String) {
    fun Dp.px(): Int = (value * density).toInt()
    val m = NativeAdMetrics
    val content = root.findViewById<LinearLayout>(R.id.ads_row_content)
    val pad = style.contentPadding
    content.setPadding(
        pad.calculateLeftPadding(direction).px(), pad.calculateTopPadding().px(),
        pad.calculateRightPadding(direction).px(), pad.calculateBottomPadding().px(),
    )
    content.minimumHeight = style.minHeight.px()

    root.findViewById<ImageView>(R.id.ads_row_icon).apply {
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
            width = style.iconSize.px(); height = style.iconSize.px(); marginEnd = m.IconGap.px()
        }
        val radius = style.iconCornerRadius.value * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
    }
    root.findViewById<TextView>(R.id.ads_row_headline).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.titleSize.value)
        setTextColor(style.titleColor.toArgb())
    }
    root.findViewById<TextView>(R.id.ads_row_body).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.bodySize.value)
        setTextColor(style.bodyColor.toArgb())
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = m.BodyTopGap.px() }
    }
    // "광고" 배지: 항상 보인다(정책). 등록 자산이 아니라 클릭되지 않는다
    root.findViewById<TextView>(R.id.ads_row_badge).apply {
        text = badgeText
        setTextSize(TypedValue.COMPLEX_UNIT_SP, m.BadgeTextSize.value)
        setTextColor(style.badgeContentColor.toArgb())
        setPadding(m.BadgeHorizontalPadding.px(), m.BadgeVerticalPadding.px(), m.BadgeHorizontalPadding.px(), m.BadgeVerticalPadding.px())
        background = roundedBackground(style.badgeBackground, m.BadgeCornerRadius.value * density)
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { marginEnd = m.BadgeGap.px() }
    }
    root.findViewById<TextView>(R.id.ads_row_cta).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, m.CtaTextSize.value)
        setTextColor(style.ctaContentColor.toArgb())
        setPadding(m.CtaHorizontalPadding.px(), 0, m.CtaHorizontalPadding.px(), 0)
        background = roundedBackground(style.ctaBackground, style.ctaCornerRadius.value * density)
        layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { height = m.RowCtaHeight.px(); marginStart = m.CtaGap.px() }
    }
}

/** 실제 행과 같은 치수의 자리표시. */
@Composable
private fun MockNativeAdRowContent(style: NativeAdRowStyle) {
    val m = NativeAdMetrics
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = style.minHeight).padding(style.contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(style.iconSize).clip(RoundedCornerShape(style.iconCornerRadius))
                .background(Color(0xFFD0D0D4)).testTag(AdTestTags.MOCK_ROW_ICON),
        )
        Spacer(Modifier.width(m.IconGap))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.testTag(AdTestTags.NATIVE_BADGE).clip(RoundedCornerShape(m.BadgeCornerRadius)).background(style.badgeBackground),
                ) {
                    Text(
                        text = stringResource(R.string.ads_native_badge),
                        modifier = Modifier.padding(horizontal = m.BadgeHorizontalPadding, vertical = m.BadgeVerticalPadding),
                        color = style.badgeContentColor, fontSize = m.BadgeTextSize, fontWeight = FontWeight.Bold, maxLines = 1,
                    )
                }
                Spacer(Modifier.width(m.BadgeGap))
                Text(
                    text = stringResource(R.string.ads_preview_row_headline),
                    modifier = Modifier.weight(1f), color = style.titleColor, fontSize = style.titleSize,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.ads_preview_row_body),
                modifier = Modifier.padding(top = m.BodyTopGap), color = style.bodyColor, fontSize = style.bodySize,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(m.CtaGap))
        Box(
            Modifier.height(m.RowCtaHeight).clip(RoundedCornerShape(style.ctaCornerRadius)).background(style.ctaBackground)
                .padding(horizontal = m.CtaHorizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.ads_preview_row_cta),
                color = style.ctaContentColor, fontSize = m.CtaTextSize, fontWeight = FontWeight.Bold, maxLines = 1,
            )
        }
    }
}
