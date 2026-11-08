package app.dot.mail

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression: MainActivity is a FragmentActivity (BiometricPrompt). biometric 1.1.0 once pulled in fragment 1.2.5,
 * whose FragmentActivity rejected the activity-result registry's request codes ("Can only use lower 16 bits for
 * requestCode") - tapping Attach in Compose crashed. Any launcher on such an activity must start without throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ActivityResultLaunchTest {
    class Host : FragmentActivity()

    @Test fun `a document picker launched from a FragmentActivity does not throw`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val picker = activity.activityResultRegistry.register("picker", ActivityResultContracts.OpenMultipleDocuments()) { _: List<Uri> -> }
        picker.launch(arrayOf("*/*"))
        picker.unregister()
    }

    @Test fun `permission, intent-sender and create-document launchers start too`() {
        val activity = Robolectric.buildActivity(Host::class.java).setup().get()
        val registry = activity.activityResultRegistry
        registry.register("perm", ActivityResultContracts.RequestPermission()) { _: Boolean -> }.launch(android.Manifest.permission.READ_CONTACTS)
        registry.register("create", ActivityResultContracts.CreateDocument("application/json")) { _: Uri? -> }.launch("backup.json")
        registry.register("start", ActivityResultContracts.StartActivityForResult()) { _ -> }.launch(Intent(Intent.ACTION_PICK))
    }
}
