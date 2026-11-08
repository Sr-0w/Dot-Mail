package app.dot.mail.data

/** Android StatFs has zero blocks in an unconfigured Robolectric filesystem. Model a 1 GiB device. */
class DotMailTestApplication:android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        org.robolectric.shadows.ShadowStatFs.registerStats(filesDir.path,262144,262144,262144)
    }
}
