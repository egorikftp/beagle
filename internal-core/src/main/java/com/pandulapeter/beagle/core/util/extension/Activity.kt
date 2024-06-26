package com.pandulapeter.beagle.core.util.extension

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.fragment.app.FragmentActivity
import com.pandulapeter.beagle.BeagleCore
import com.pandulapeter.beagle.core.OverlayFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Suppress("SpellCheckingInspection")
private val excludedPackageNames = listOf(
    "com.pandulapeter.beagle.logCrash.implementation.DebugMenuActivity",
    "com.pandulapeter.beagle.core.view.gallery.GalleryActivity",
    "com.pandulapeter.beagle.core.view.bugReport.BugReportActivity",
    "com.google.android.gms.auth.api.signin.internal.SignInHubActivity",
    "com.gigya.android.sdk.ui.HostActivity",
    "com.clevertap.android.sdk.InAppNotificationActivity",
    "com.facebook.FacebookActivity",
    "com.markodevcic.peko.PekoActivity"
)

internal val Activity.supportsDebugMenu
    get() = this is FragmentActivity
            && excludedPackageNames.none { componentName.className.startsWith(it) }
            && BeagleCore.implementation.behavior.shouldAddDebugMenu(this)

internal fun Activity.shareFile(uri: Uri, fileType: String, email: String? = null) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = fileType
        if (email != null) {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_STREAM, uri)
    }, null))
}

internal fun Activity.shareFiles(uris: List<Uri>) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        BeagleCore.implementation.behavior.bugReportingBehavior.emailAddress?.let { email ->
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }, null))
}

internal suspend fun Activity.createAndShareLogFile(fileName: String, content: String) = withContext(Dispatchers.IO) {
    createLogFile(fileName, content)?.let { uri -> shareFile(uri, "text/plain") }
}

internal fun Activity.takeScreenshotWithMediaProjectionManager(fileName: String) {
    (BeagleCore.implementation.uiManager.findOverlayFragment(this as? FragmentActivity?) as? OverlayFragment?).let { overlayFragment ->
        overlayFragment?.startCapture(false, fileName) ?: BeagleCore.implementation.onScreenCaptureReady?.invoke(null)
    }
}

internal fun Activity.recordScreenWithMediaProjectionManager(fileName: String) {
    (BeagleCore.implementation.uiManager.findOverlayFragment(this as? FragmentActivity?) as? OverlayFragment?).let { overlayFragment ->
        overlayFragment?.startCapture(true, fileName) ?: BeagleCore.implementation.onScreenCaptureReady?.invoke(null)
    }
}

internal fun Activity.getScreenSize() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Triple(
    windowManager.currentWindowMetrics.bounds.width(),
    windowManager.currentWindowMetrics.bounds.height(),
    resources.displayMetrics.densityDpi
) else Triple(
    resources.displayMetrics.widthPixels,
    resources.displayMetrics.heightPixels,
    resources.displayMetrics.densityDpi
)