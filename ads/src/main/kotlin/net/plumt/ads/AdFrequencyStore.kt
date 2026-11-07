package net.plumt.ads

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * 빈도 정책이 표시 기록(마지막 표시 시각·실행 횟수)을 남기는 저장소. 교체 지점이다.
 *
 * 값은 키마다 `Long` 하나다. 읽기·쓰기는 메인 스레드에서 짧게 끝나야 한다(기본 구현은 메모리 캐시를 읽고 쓰기는 비동기로 디스크에 반영한다).
 * 구현이 던진 예외는 삼키지 않고 [FullScreenAdController.show] 호출부로 전파된다.
 */
public interface AdFrequencyStore {
    /** [key] 의 값. 없으면 null. */
    public fun getLong(key: String): Long?

    /** [key] 에 [value] 를 기록한다. */
    public fun putLong(key: String, value: Long)

    public companion object {
        /** 기본 SharedPreferences 파일 이름. */
        public const val DEFAULT_FILE_NAME: String = "net.plumt.ads.frequency"

        /** 프로세스 안에서만 사는 저장소. 프로세스가 끝나면 기록이 사라진다(테스트·영속이 필요 없는 정책용). */
        public fun inMemory(): AdFrequencyStore = InMemoryFrequencyStore()

        /**
         * 앱 전용 SharedPreferences 파일에 기록한다. 프로세스 재시작·기기 재부팅을 넘어 유지된다. [AdsClient.create] 의 기본값이다.
         * 파일은 처음 읽거나 쓸 때 연다.
         *
         * @throws IllegalArgumentException [fileName] 이 비었을 때
         */
        public fun sharedPreferences(context: Context, fileName: String = DEFAULT_FILE_NAME): AdFrequencyStore {
            require(fileName.isNotBlank()) { "fileName 이 비었습니다" }
            return SharedPreferencesFrequencyStore(context.applicationContext, fileName)
        }
    }
}

internal class InMemoryFrequencyStore : AdFrequencyStore {
    private val values = ConcurrentHashMap<String, Long>()
    override fun getLong(key: String): Long? = values[key]
    override fun putLong(key: String, value: Long) {
        values[key] = value
    }
}

internal class SharedPreferencesFrequencyStore(
    private val context: Context,
    private val fileName: String,
) : AdFrequencyStore {
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(fileName, Context.MODE_PRIVATE) }

    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }
}
