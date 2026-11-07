package net.plumt.ads.network

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import net.plumt.ads.AdsConfig
import net.plumt.ads.AdsException

/**
 * 광고 네트워크 한 벌. 기본 구현은 [net.plumt.ads.mobileads.MobileAdsNetwork] 이고, 다른 네트워크·중개(mediation)
 * 계층을 쓰려면 이 인터페이스를 구현해 [net.plumt.ads.AdsClient.create] 에 넘긴다.
 * 메서드는 메인 스레드에서 불린다.
 */
public interface AdNetwork {
    /**
     * SDK 를 초기화한다. 이미 초기화했으면 곧바로 끝난다.
     * [config] 는 클라이언트가 모드를 정한 뒤의 값이다 — [AdsConfig.mode] 는 `Development`·`Production` 중 하나이고,
     * 운영 모드면 시험 기기 ID·동의 디버그 지역이 지워져 있다.
     *
     * @throws AdsException 초기화 실패([net.plumt.ads.AdsErrorKind.Initialization]) 또는
     *   설정이 모드와 맞지 않음([net.plumt.ads.AdsErrorKind.Configuration])
     */
    public suspend fun initialize(context: Context, config: AdsConfig)

    /** 앞면 광고(전면). */
    public val interstitial: FullScreenAdProvider

    /** 보상형 광고. */
    public val rewarded: FullScreenAdProvider

    /** 앱 오프닝 광고. */
    public val appOpen: FullScreenAdProvider

    /** 배너. */
    public val banner: BannerProvider

    /** 네이티브. */
    public val native: NativeAdProvider
}

/** 불러온 전면형(전체 화면) 광고 하나. 한 번 보이면 다시 쓸 수 없다. */
public interface FullScreenAd {
    /** 화면에 올린다. [activity] 는 호출 동안만 쓰고 붙들지 않는다. */
    public fun show(activity: Activity, callbacks: FullScreenAdCallbacks)

    /** 참조를 놓는다. 이후 [show] 는 부르지 않는다. */
    public fun destroy()
}

/** [FullScreenAd] 표시 중 사건. 필요한 것만 재정의한다. */
public interface FullScreenAdCallbacks {
    public fun onShown() {}
    public fun onImpression() {}
    public fun onClicked() {}
    public fun onDismissed() {}
    public fun onShowFailed(error: AdsException) {}

    /** 보상형에서 보상 조건을 채웠다. */
    public fun onRewardEarned(type: String, amount: Int) {}
}

/** 전면형 광고를 불러오는 공급자. */
public fun interface FullScreenAdProvider {
    /**
     * 광고 하나를 불러온다. 코루틴이 취소되면 불러온 광고를 버린다.
     *
     * @param context 애플리케이션 컨텍스트
     * @throws AdsException 불러오기 실패([net.plumt.ads.AdsErrorKind.Load])
     */
    public suspend fun load(context: Context, adUnitId: String): FullScreenAd
}

/** 배너 사건. */
public interface BannerListener {
    public fun onLoaded() {}
    public fun onLoadFailed(error: AdsException) {}
    public fun onImpression() {}
    public fun onClicked() {}
}

/** 화면에 붙일 배너 하나. */
public interface BannerHandle {
    /** 뷰 계층에 붙일 뷰. */
    public val view: View

    /** 광고가 도착했을 때 차지하는 높이(dp). */
    public val heightDp: Int

    /** 광고를 요청한다. */
    public fun load()
    public fun pause()
    public fun resume()

    /** 참조를 놓는다. 뷰는 더 쓰지 않는다. */
    public fun destroy()
}

/** 배너 공급자. */
public fun interface BannerProvider {
    /**
     * 화면 폭에 맞는 앵커드 적응형 배너를 만든다. 요청은 [BannerHandle.load] 가 보낸다.
     *
     * @param context 이 배너가 사는 화면의 컨텍스트. [BannerHandle.destroy] 까지 붙들린다
     * @param widthDp 배너 폭(dp)
     * @throws AdsException 만들기 실패([net.plumt.ads.AdsErrorKind.Load])
     */
    public fun create(context: Context, adUnitId: String, widthDp: Int, listener: BannerListener): BannerHandle
}

/**
 * 네이티브 광고의 글·이미지 자산. 없는 것은 null.
 *
 * @property hasVideo 동영상 소재다. 동영상 광고는 미디어 뷰(기본 구현에서는 `MediaView`) 없이 보이면 정책 위반이라
 *   [NativeAdHandle.bind] 가 미디어 뷰 없는 레이아웃에 묶기를 거부한다
 */
public class NativeAdAssets(
    public val headline: String?,
    public val body: String?,
    public val callToAction: String?,
    public val advertiser: String?,
    public val price: String?,
    public val store: String?,
    public val starRating: Double?,
    public val icon: Drawable?,
    public val hasVideo: Boolean = false,
)

/** 앱 레이아웃 안의 뷰. 레이아웃에 없는 자산은 null 로 둔다. */
public class NativeAdViews(
    public val headline: View? = null,
    public val body: View? = null,
    public val callToAction: View? = null,
    public val advertiser: View? = null,
    public val price: View? = null,
    public val store: View? = null,
    public val starRating: View? = null,
    public val icon: View? = null,
    /** 미디어(큰 이미지·동영상) 뷰. 기본 구현에서는 `MediaView` 여야 하고, 가로·세로 120dp 이상으로 둔다. */
    public val media: View? = null,
)

/** 불러온 네이티브 광고 하나. 뷰 하나에만 묶을 수 있다. */
public interface NativeAdHandle {
    /** 글·이미지 자산. 텍스트를 직접 그리려는 호출부가 읽는다. */
    public val assets: NativeAdAssets

    /**
     * [container] 안에 이미 올라간 [views] 에 이 광고를 묶는다. 네트워크가 요구하는 래퍼 뷰는 구현이 알아서 끼운다.
     * "광고" 표시는 앱 레이아웃이 보이게 둔다. 다시 묶으면 이전에 끼운 래퍼 뷰는 놓는다.
     *
     * @return 묶었으면 true. 정책상 이 레이아웃에 보일 수 없는 광고(미디어 뷰 없는 레이아웃의 동영상 광고 등)면
     *   [container] 를 건드리지 않고 false — 호출부는 이 광고를 보이지 않는다
     * @throws IllegalArgumentException [views] 가 구현이 요구하는 형식이 아닐 때
     */
    public fun bind(container: ViewGroup, views: NativeAdViews): Boolean

    /** 참조를 놓는다. */
    public fun destroy()
}

/** 네이티브 광고 공급자. */
public fun interface NativeAdProvider {
    /**
     * 최대 [count] 개를 한 번에 요청한다. 일부만 채워지면 채워진 만큼 돌려준다.
     * 코루틴이 취소되면 도착한 광고를 모두 버린다.
     *
     * @param context 애플리케이션 컨텍스트
     * @throws AdsException 하나도 받지 못했을 때([net.plumt.ads.AdsErrorKind.Load])
     */
    public suspend fun load(context: Context, adUnitId: String, count: Int): List<NativeAdHandle>

    /**
     * 이 네트워크의 미디어 뷰(큰 이미지·동영상)를 만든다. 라이브러리 템플릿이 [NativeAdViews.media] 자리에 끼운다.
     * 미디어 뷰를 지원하지 않으면 null(기본) — 템플릿은 미디어 없이 그리고, 동영상 광고는 그리지 않는다.
     */
    public fun createMediaView(context: Context): View? = null
}
