package net.plumt.ads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * 네트워크 연결 상태. [AdErrorReason.Network] 로 실패한 불러오기는 연결이 돌아오길 기다렸다 다시 시도한다.
 * 기본은 [system](시스템 연결 관리자). 시험·다른 판단 기준이면 교체한다.
 */
public fun interface AdConnectivity {
    /** 현재 연결 여부와 이후 변경. 구독하면 곧바로 현재 값이 와야 한다. */
    public fun online(): Flow<Boolean>

    public companion object {
        /** 늘 연결된 것으로 본다(연결 대기 없이 보통 간격으로 재시도). */
        public val AlwaysOnline: AdConnectivity = AdConnectivity { flowOf(true) }

        /**
         * 시스템 연결 관리자 기반. `ACCESS_NETWORK_STATE` 권한이 필요하다(광고 SDK 가 매니페스트에 이미 선언한다).
         * 인터넷 능력이 있고 시스템이 연결을 확인(`VALIDATED`)한 망이 하나라도 있으면 연결된 것이다.
         * 권한이 없거나 서비스가 없으면 늘 연결된 것으로 본다 — 연결 대기 대신 보통 간격 재시도로 물러난다.
         */
        public fun system(context: Context): AdConnectivity {
            val app = context.applicationContext
            return AdConnectivity { systemOnline(app) }
        }
    }
}

private fun systemOnline(context: Context): Flow<Boolean> {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return flowOf(true)
    return callbackFlow {
        fun current(): Boolean = try {
            cm.getNetworkCapabilities(cm.activeNetwork).usable()
        } catch (_: SecurityException) {
            true
        }
        val tracker = UsableNetworks(initial = current())
        // 망마다 쓸 수 있는지를 따로 들고 판정한다. onLost 시점에 activeNetwork 를 다시 물으면 아직 잃은 망을
        // 가리켜 연결된 것으로 답할 수 있다(오프라인 전환을 놓친다)
        val callback = object : ConnectivityManager.NetworkCallback() {
            // API 26 미만은 onAvailable 뒤 onCapabilitiesChanged 가 온다는 보장이 없어 여기서도 능력을 묻는다
            override fun onAvailable(network: Network) {
                val usable = try {
                    cm.getNetworkCapabilities(network).usable()
                } catch (_: SecurityException) {
                    true
                }
                trySend(tracker.update(network, usable))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(tracker.update(network, networkCapabilities.usable()))
            }

            override fun onLost(network: Network) {
                trySend(tracker.lost(network))
            }

            override fun onUnavailable() {
                trySend(tracker.unavailable())
            }
        }
        trySend(tracker.online())
        val registered = try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
            true
        } catch (_: SecurityException) {
            trySend(true)
            false
        }
        awaitClose { if (registered) cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

/** 인터넷 능력이 있고 시스템이 실제 연결을 확인(VALIDATED)한 망만 쓸 수 있다고 본다. */
private fun NetworkCapabilities?.usable(): Boolean =
    this != null && usableCapabilities(
        internet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )

internal fun usableCapabilities(internet: Boolean, validated: Boolean): Boolean = internet && validated

/**
 * 망별 사용 가능 여부. 콜백으로 알게 된 망이 하나라도 쓸 수 있으면 연결된 것이다.
 * 아직 어떤 망도 콜백으로 오지 않았으면 등록 직전 조회값([initial])을 쓴다.
 * 잃은 망은 바로 빼고 판정한다 — 그 시점의 activeNetwork 를 믿지 않는다.
 */
internal class UsableNetworks(private val initial: Boolean) {
    private val networks = LinkedHashMap<Any, Boolean>()
    private var heard = false

    @Synchronized
    fun update(network: Any, usable: Boolean): Boolean {
        heard = true
        networks[network] = usable
        return online()
    }

    @Synchronized
    fun lost(network: Any): Boolean {
        heard = true
        networks.remove(network)
        return online()
    }

    /** 쓸 수 있는 망을 찾지 못했다는 알림. 알고 있는 망만으로 판정한다(없으면 오프라인). */
    @Synchronized
    fun unavailable(): Boolean {
        heard = true
        return online()
    }

    @Synchronized
    fun online(): Boolean = if (heard) networks.values.any { it } else initial
}
