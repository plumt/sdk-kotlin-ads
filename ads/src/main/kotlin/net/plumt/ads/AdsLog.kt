package net.plumt.ads

import android.util.Log

/** 라이브러리 로그. 광고 단위 ID 는 비밀이 아니지만 사용자 식별 정보는 싣지 않는다. */
internal object AdsLog {
    const val TAG = "PlumtAds"

    fun info(message: String) {
        Log.i(TAG, message)
    }

    fun warn(message: String) {
        Log.w(TAG, message)
    }

    fun error(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
    }
}
