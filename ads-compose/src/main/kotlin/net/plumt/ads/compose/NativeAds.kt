package net.plumt.ads.compose

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import net.plumt.ads.AdFormat
import net.plumt.ads.AdUnit
import net.plumt.ads.AdsException
import net.plumt.ads.PreloadPolicy
import net.plumt.ads.network.NativeAdHandle
import net.plumt.ads.network.NativeAdViews

/** 한 화면의 목록 광고 슬롯이 나눠 쓰는 네이티브 광고 묶음. [rememberNativeAdPool] 로 만든다. */
@Stable
public class NativeAdPool internal constructor(public val size: Int) {
    private var ads by mutableStateOf<List<NativeAdHandle>>(emptyList())

    /** 불러오기 상태. 실패하면 원인([AdsException.reason])이 들어 있다. */
    public var state: AdSlotState by mutableStateOf(AdSlotState.Idle)
        internal set

    /** 요청이 끝나지 않았으면 true. */
    public val loading: Boolean get() = state == AdSlotState.Loading

    /** [slot] 의 광고. 아직 없거나 채울 광고가 모자라면 null. 같은 슬롯은 늘 같은 광고다. */
    public operator fun get(slot: Int): NativeAdHandle? = ads.getOrNull(slot)

    internal fun fill(list: List<NativeAdHandle>) {
        ads.forEach { it.destroy() }
        ads = list
    }

    internal fun destroyAll() {
        ads.forEach { it.destroy() }
        ads = emptyList()
        state = AdSlotState.Idle
    }
}

/** 한 요청으로 받을 수 있는 광고 수 상한. */
public const val MAX_NATIVE_POOL_SIZE: Int = 5

/** 네이티브 풀 기본 갱신 주기: 1시간. */
public const val DEFAULT_NATIVE_REFRESH_MILLIS: Long = 3_600_000L

/**
 * [size] 개 슬롯 분량의 네이티브 광고를 한 번의 요청으로 받아 둔다. 목록 바깥에서 한 번 부른다.
 *
 * - 광고를 쓸 수 없거나 단위가 모드와 맞지 않으면 요청 없이 빈 묶음이다. 쓸 수 있게 되면 그때 요청한다
 * - 실패하면 분류별로 다시 시도하고([retry]), [fallbacks] 가 있으면 그 단위로 넘어간다. 상태는 [NativeAdPool.state]
 * - 컴포지션을 떠나거나 광고를 쓸 수 없게 되면 모두 destroy 한다
 * - 받은 지 [refreshAfterMillis] 가 지나면 버리고 다시 요청한다
 * - 미리보기에서는 요청하지 않는다(행·카드 컴포저블이 자리표시를 그린다)
 *
 * @param size 1..[MAX_NATIVE_POOL_SIZE]
 * @param refreshAfterMillis 1 이상. 기본 1시간
 * @param onLoadFailed 모든 단위가 실패할 때마다 메인 스레드에서 불린다
 * @throws IllegalArgumentException 단위가 네이티브가 아니거나 [size]·[refreshAfterMillis] 가 범위 밖일 때
 */
@Composable
public fun rememberNativeAdPool(
    unit: AdUnit,
    size: Int,
    placement: String = unit.name,
    refreshAfterMillis: Long = DEFAULT_NATIVE_REFRESH_MILLIS,
    fallbacks: List<AdUnit> = emptyList(),
    retry: PreloadPolicy = PreloadPolicy(),
    onLoadFailed: (AdsException) -> Unit = {},
): NativeAdPool {
    unit.requireFormat(AdFormat.Native)
    fallbacks.forEach { it.requireFormat(AdFormat.Native) }
    require(size in 1..MAX_NATIVE_POOL_SIZE) { "size 는 1..$MAX_NATIVE_POOL_SIZE 이어야 합니다: $size" }
    require(refreshAfterMillis >= 1) { "refreshAfterMillis 는 1 이상이어야 합니다: $refreshAfterMillis" }
    val pool = remember(unit, size, fallbacks) { NativeAdPool(size) }
    if (adPreviewVisible() != null) return pool

    val client = requireAdsClient()
    val available by client.adsAvailable.collectAsState()
    val context = LocalContext.current.applicationContext
    val failed by rememberUpdatedState(onLoadFailed)
    val ids = remember(client, unit, fallbacks, placement) {
        (listOf(unit) + fallbacks).distinct().mapNotNull { client.adUnitId(it, placement) }
    }

    DisposableEffect(pool) { onDispose { pool.destroyAll() } }
    LaunchedEffect(pool, available, ids) {
        if (!available) {
            pool.destroyAll()
            return@LaunchedEffect
        }
        pool.runLoop(ids, placement, client.events, retry, refreshAfterMillis, { client.connectivity.online() }, { failed(it) }) { id ->
            client.network.native.load(context, id, size)
        }
    }
    return pool
}

/**
 * 네이티브 광고 한 장. 숨김·불러오는 중·실패면 null. 화면을 떠나면 destroy 한다.
 * 종료 팝업처럼 뜨는 순간 광고가 있어야 하면 [rememberExitAd] 를 쓴다.
 */
@Composable
public fun rememberNativeAd(
    unit: AdUnit,
    placement: String = unit.name,
    fallbacks: List<AdUnit> = emptyList(),
    onLoadFailed: (AdsException) -> Unit = {},
): NativeAdHandle? = rememberNativeAdPool(unit, 1, placement, fallbacks = fallbacks, onLoadFailed = onLoadFailed)[0]

/**
 * 앱 레이아웃 안의 뷰 id. 레이아웃에 없는 자산은 [View.NO_ID] 로 둔다.
 * [media] 는 기본 구현에서 `MediaView` 여야 한다.
 */
public class NativeAdViewIds(
    @param:IdRes public val headline: Int = View.NO_ID,
    @param:IdRes public val body: Int = View.NO_ID,
    @param:IdRes public val callToAction: Int = View.NO_ID,
    @param:IdRes public val advertiser: Int = View.NO_ID,
    @param:IdRes public val price: Int = View.NO_ID,
    @param:IdRes public val store: Int = View.NO_ID,
    @param:IdRes public val starRating: Int = View.NO_ID,
    @param:IdRes public val icon: Int = View.NO_ID,
    @param:IdRes public val media: Int = View.NO_ID,
)

/**
 * 앱 레이아웃으로 그리는 네이티브 광고 슬롯. 레이아웃 없이 쓰려면 기성 카드([NativeAdRow]·[NativeAdListItem])를 쓴다.
 * [pool] 의 [slot] 에 광고가 없으면 [failure]·[placeholder] 에 따라 자리표시를 그리거나 아무것도 그리지 않는다.
 * 레이아웃에 미디어 뷰([NativeAdViewIds.media])가 없는데 동영상 광고가 오면 정책상 보일 수 없어 그리지 않는다.
 * 미리보기에서는 레이아웃을 묶지 않은 채 그대로 보인다(광고 없음 미리보기면 그리지 않는다).
 *
 * 광고임을 알리는 표시("광고" 배지 등)는 앱 레이아웃에 두고 숨기지 않는다. 미디어 뷰는 가로·세로 120dp 이상으로 두고,
 * 오른쪽 위 모서리(AdChoices 아이콘 자리)에 다른 뷰를 겹치지 않는다.
 *
 * @param viewIds [layoutRes] 안의 자산 뷰 id
 * @param failure 실패했을 때 [placeholder] 를 남길지 접을지. 기본은 접는다
 * @param placeholder 불러오는 중(그리고 [AdSlotFailure.KeepSpace] 면 실패 뒤)에 대신 그릴 것. null 이면 아무것도 그리지 않는다
 */
@Composable
public fun NativeAdSlot(
    pool: NativeAdPool,
    slot: Int,
    @LayoutRes layoutRes: Int,
    viewIds: NativeAdViewIds,
    modifier: Modifier = Modifier,
    failure: AdSlotFailure = AdSlotFailure.Collapse,
    placeholder: (@Composable () -> Unit)? = null,
) {
    val preview = adPreviewVisible()
    if (preview != null) {
        if (preview) AndroidView(modifier = modifier, factory = { ctx -> LayoutInflater.from(ctx).inflate(layoutRes, null) })
        return
    }
    val ad = pool[slot]?.takeUnless { viewIds.media == View.NO_ID && it.assets.hasVideo }
    when (slotDraw(ad != null, pool.state, failure, placeholder != null)) {
        SlotDraw.Placeholder -> placeholder?.invoke()
        SlotDraw.Nothing -> Unit
        SlotDraw.Ad -> AndroidView(
            modifier = modifier,
            factory = { ctx -> FrameLayout(ctx) },
            update = { container ->
                val bound = ad ?: return@AndroidView
                if (container.getTag(TAG_BOUND) !== bound) {
                    container.removeAllViews()
                    LayoutInflater.from(container.context).inflate(layoutRes, container, true)
                    if (bound.bind(container, container.findViews(viewIds))) {
                        container.setTag(TAG_BOUND, bound)
                    } else {
                        container.removeAllViews()
                    }
                }
            },
        )
    }
}

private fun View.findViews(ids: NativeAdViewIds): NativeAdViews {
    fun find(id: Int): View? = if (id == View.NO_ID) null else findViewById(id)
    return NativeAdViews(
        headline = find(ids.headline),
        body = find(ids.body),
        callToAction = find(ids.callToAction),
        advertiser = find(ids.advertiser),
        price = find(ids.price),
        store = find(ids.store),
        starRating = find(ids.starRating),
        icon = find(ids.icon),
        media = find(ids.media),
    )
}

// 뷰 태그 키는 리소스 id 여야 한다. 라이브러리 ids.xml 에 선언해 앱 id 와 겹치지 않게 한다
internal val TAG_BOUND = R.id.net_plumt_ads_native_bound
