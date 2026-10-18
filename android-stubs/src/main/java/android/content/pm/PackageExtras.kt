package android.content.pm

class ApplicationInfo {
    var packageName: String? = null
    var sourceDir: String? = null
    var publicSourceDir: String? = null
    var dataDir: String? = null
    var metaData: android.os.Bundle? = null
    var nonLocalizedLabel: CharSequence? = null
    var labelRes: Int = 0
    var iconRes: Int = 0
}

class FeatureInfo(var name: String? = null) {
    override fun toString(): String = "FeatureInfo{name=$name}"
}

class PackageInfo {
    var packageName: String? = null
    var versionName: String? = null
    var versionCode: Int = 0
    var versionCodeMajor: Int = 0
    var applicationInfo: ApplicationInfo? = null
    var reqFeatures: Array<FeatureInfo>? = null
    var sharedUserId: String? = null
}
