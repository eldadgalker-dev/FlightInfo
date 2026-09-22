package android.content.pm
class PackageInfo { var versionName: String? = null }
class PackageManager { fun getPackageInfo(n: String, f: Int): PackageInfo = PackageInfo() }
