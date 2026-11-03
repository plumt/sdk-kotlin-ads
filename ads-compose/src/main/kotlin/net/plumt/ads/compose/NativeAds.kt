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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import net.plumt.ads.AdEvent
import net.plumt.ads.AdFormat
import net.plumt.ads.AdsException
import net.plumt.ads.network.NativeAdHandle
import net.plumt.ads.network.NativeAdViews

/** 한 화면의 목록 광고 슬롯이 나눠 쓰는 네이티브 광고 묶음. [rememberNativeAdPool] 로 만든다. */
@Stable
public class NativeAdPool internal constructor(public val size: Int) {
    private var ads by mutableStateOf<List<NativeAdHandle>>(emptyList())

    /** 요청이 끝나지 않았으면 true. */
    public var loading: Boolean by mutableStateOf(false)
        internal set

    /** [slot] 의 광고. 아직 없거나 채울 광고가 모자라면 null. 같은 슬롯은 늘 같은 광고다. */
    public operator fun get(slot: Int): NativeAdHandle? = ads.getOrNull(slot)

    internal fun fill(list: List<NativeAdHandle>) {
        ads = list
    }

    internal fun destroyAll() {
        ads.forEach { it.destroy() }
        ads = emptyList()
        loading = false
    }
}

/** 한 요청으로 받을 수 있는 광고 수 상한. */
public const val MAX_NATIVE_POOL_SIZE: Int = 5

/**
 * [size] 개 슬롯 분량의 네이티브 광고를 한 번의 요청으로 받아 둔다. 목록 바깥에서 한 번 부른다.
 *
 * - 광고를 쓸 수 없으면 요청 없이 빈 묶음이다. 쓸 수 있게 되면 그때 요청한다
 * - 컴포지션을 떠나거나 광고를 쓸 수 없게 되면 모두 destroy 한다
 *
 * @param size 1..[MAX_NATIVE_POOL_SIZE]
 * @throws IllegalArgumentException [adUnitId] 가 비었거나 [size] 가 범위 밖일 때
 */
@Composable
public fun rememberNativeAdPool(adUnitId: String, size: Int, placement: String = "native"): NativeAdPool {
    require(adUnitId.isNotBlank()) { "adUnitId 가 비었습니다: placement=$placement" }
    require(size in 1..MAX_NATIVE_POOL_SIZE) { "size 는 1..$MAX_NATIVE_POOL_SIZE 이어야 합니다: $size" }
    val client = LocalAdsClient.current
    val available by client.adsAvailable.collectAsState()
    val context = LocalContext.current.applicationContext
    val pool = remember(client, adUnitId, size) { NativeAdPool(size) }

    DisposableEffect(pool) { onDispose { pool.destroyAll() } }
    LaunchedEffect(pool, available) {
        if (!available) {
            pool.destroyAll()
            return@LaunchedEffect
        }
        pool.loading = true
        client.events.onEvent(AdEvent.Requested(AdFormat.Native, placement))
        try {
            val loaded = client.network.native.load(context, adUnitId, size)
            pool.fill(loaded)
            client.events.onEvent(AdEvent.Loaded(AdFormat.Native, placement))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdsException) {
            client.events.onEvent(AdEvent.LoadFailed(AdFormat.Native, placement, e.code, e.message.orEmpty()))
        } finally {
            pool.loading = false
        }
    }
    return pool
}

/**
 * 앱 레이아웃 안의 뷰 id. 레이아웃에 없는 자산은 [View.NO_ID] 로 둔다.
 * [media] 는 기본 구현에서 `MediaView` 여야 한다.
 */
public class NativeAdViewIds(
    @IdRes public val headline: Int = View.NO_ID,
    @IdRes public val body: Int = View.NO_ID,
    @IdRes public val callToAction: Int = View.NO_ID,
    @IdRes public val advertiser: Int = View.NO_ID,
    @IdRes public val price: Int = View.NO_ID,
    @IdRes public val store: Int = View.NO_ID,
    @IdRes public val starRating: Int = View.NO_ID,
    @IdRes public val icon: Int = View.NO_ID,
    @IdRes public val media: Int = View.NO_ID,
)

/**
 * 네이티브 광고 슬롯. 모양은 앱이 [layoutRes] 로 정한다. [pool] 의 [slot] 에 광고가 없으면 그리지 않는다.
 *
 * 광고임을 알리는 표시("광고" 배지 등)는 앱 레이아웃에 두고 숨기지 않는다.
 *
 * @param viewIds [layoutRes] 안의 자산 뷰 id
 */
@Composable
public fun NativeAdSlot(
    pool: NativeAdPool,
    slot: Int,
    @LayoutRes layoutRes: Int,
    viewIds: NativeAdViewIds,
    modifier: Modifier = Modifier,
) {
    val ad = pool[slot] ?: return
    AndroidView(
        modifier = modifier,
        factory = { ctx -> FrameLayout(ctx) },
        update = { container ->
            if (container.getTag(TAG_BOUND) !== ad) {
                container.removeAllViews()
                LayoutInflater.from(container.context).inflate(layoutRes, container, true)
                ad.bind(container, container.findViews(viewIds))
                container.setTag(TAG_BOUND, ad)
            }
        },
    )
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

// 뷰 태그 키는 리소스 id 여야 한다. 라이브러리가 리소스를 싣지 않으려고 고정 정수를 쓴다(앱 id 와 충돌하지 않는 상위 비트).
private const val TAG_BOUND = 0x7F_FF_00_01
