package dev.kortex.myinfo.topics.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.kortex.myinfo.topics.domain.port.Connectivity

/** [Connectivity] from the system: a network that says it reaches the internet counts as online. */
class AndroidConnectivity(context: Context) : Connectivity {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val network = manager?.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
