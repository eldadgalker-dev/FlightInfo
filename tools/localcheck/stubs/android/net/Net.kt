package android.net
class Uri { companion object { fun parse(s: String): Uri = Uri() } }
class NetworkCapabilities { fun hasCapability(c: Int): Boolean = true; companion object { const val NET_CAPABILITY_INTERNET = 12; const val NET_CAPABILITY_VALIDATED = 16 } }
class Network
class ConnectivityManager { val activeNetwork: Network? = null; fun getNetworkCapabilities(n: Network?): NetworkCapabilities? = null }
