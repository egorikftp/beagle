package com.pandulapeter.beagle.core.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.view.Surface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import com.pandulapeter.beagle.BeagleCore
import com.pandulapeter.beagle.core.R
import com.pandulapeter.beagle.core.util.extension.createScreenCaptureFile
import com.pandulapeter.beagle.core.util.extension.createScreenshotFromBitmap
import com.pandulapeter.beagle.core.util.extension.getScreenSize
import com.pandulapeter.beagle.core.util.extension.getUriForFile
import com.pandulapeter.beagle.core.util.extension.text
import com.pandulapeter.beagle.core.view.gallery.GalleryActivity
import com.pandulapeter.beagle.core.view.gallery.MediaPreviewDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

internal class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private val handlerThread = HandlerThread(javaClass.simpleName, Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var handler: Handler
    private lateinit var file: File
    private val notificationChannelId get() = BeagleCore.implementation.behavior.screenCaptureBehavior.serviceNotificationChannelId

    override fun onCreate() {
        super.onCreate()
        handlerThread.start()
        handler = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        when (intent.action) {
            ACTION_DONE -> onReady(getUriForFile(file))
            null -> {
                val isForVideo = intent.getBooleanExtra(EXTRA_IS_FOR_VIDEO, false)
                moveToForeground(isForVideo)
                handler.postDelayed({
                    startCapture(
                        resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                        resultData = intent.getParcelableExtra(EXTRA_RESULT_INTENT)!!,
                        isForVideo = isForVideo,
                        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "fileName"
                    )
                }, SCREENSHOT_DELAY)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = throw IllegalStateException("Binding not supported.")

    private fun cleanUp() {
        projection?.stop()
        try {
            mediaRecorder?.stop()
        } catch (_: RuntimeException) {
        }
        mediaRecorder?.release()
        mediaRecorder = null
        virtualDisplay?.release()
        projection = null
    }

    private fun startCapture(resultCode: Int, resultData: Intent, isForVideo: Boolean, fileName: String) {
        projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager?)?.getMediaProjection(resultCode, resultData)
        BeagleCore.implementation.currentActivity?.getScreenSize()?.let { (widthPixels, heightPixels, density) ->
            var downscaledWidth = widthPixels
            var downscaledHeight = heightPixels
            while (downscaledWidth > 1920 && downscaledHeight > 1920) {
                downscaledWidth = (downscaledWidth * 0.75f).roundToInt()
                downscaledHeight = (downscaledHeight * 0.75f).roundToInt()
            }
            downscaledWidth = (downscaledWidth / 2) * 2
            downscaledHeight = (downscaledHeight / 2) * 2
            if (isForVideo) {
                @Suppress("DEPRECATION")
                mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()).apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(downscaledWidth, downscaledHeight)
                    setVideoEncodingBitRate(6000)
                    file = createScreenCaptureFile(fileName)
                    setOutputFile(file.absolutePath)
                    try {
                        prepare()
                    } catch (_: IllegalStateException) {
                    } catch (_: IOException) {
                        onReady(null)
                    }
                }
                createVirtualDisplay(downscaledWidth, downscaledHeight, density, mediaRecorder?.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
                try {
                    mediaRecorder?.start()
                } catch (_: IllegalStateException) {
                    onReady(null)
                }
            } else {
                val screenshotWriter = ScreenshotWriter(downscaledWidth, downscaledHeight, handler) { bitmap ->
                    GlobalScope.launch(Dispatchers.IO) {
                        (createScreenshotFromBitmap(bitmap, fileName))?.let { uri ->
                            if (BeagleCore.implementation.onScreenCaptureReady != null) {
                                launch(Dispatchers.Main) {
                                    onReady(uri)
                                }
                            }
                        }
                    }
                }
                createVirtualDisplay(
                    downscaledWidth,
                    downscaledHeight,
                    density,
                    screenshotWriter.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                )
            }
            if (virtualDisplay?.surface == null) {
                onReady(null)
            }
        }
    }

    private fun moveToForeground(isForVideo: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager.getNotificationChannel(notificationChannelId) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    notificationChannelId,
                    text(BeagleCore.implementation.appearance.screenCaptureTexts.notificationChannelName),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(null, null)
                }
            )
        }
        if (isForVideo) {
            BeagleCore.implementation.appearance.screenCaptureTexts.toastText?.let { Toast.makeText(this, text(it), Toast.LENGTH_SHORT).show() }
        }
        startForeground(
            RECORDING_NOTIFICATION_ID,
            NotificationCompat.Builder(this, notificationChannelId)
                .setAutoCancel(false)
                .setSound(null)
                .setSmallIcon(R.drawable.beagle_ic_recording)
                .setDefaults(Notification.DEFAULT_ALL)
                .setContentTitle(text(BeagleCore.implementation.appearance.screenCaptureTexts.inProgressNotificationTitle))
                .setPriority(if (isForVideo) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_MIN)
                .setSilent(!isForVideo)
                .apply {
                    if (isForVideo) {
                        addAction(
                            NotificationCompat.Action(
                                R.drawable.beagle_ic_recording,
                                text(BeagleCore.implementation.appearance.screenCaptureTexts.inProgressNotificationStop),
                                PendingIntent.getService(
                                    this@ScreenCaptureService,
                                    0,
                                    Intent(this@ScreenCaptureService, ScreenCaptureService::class.java).setAction(ACTION_DONE),
                                    FLAG_IMMUTABLE
                                )
                            )
                        )
                        setStyle(NotificationCompat.BigTextStyle().bigText(text(BeagleCore.implementation.appearance.screenCaptureTexts.inProgressNotificationContent)))
                    }
                }
                .build()
        )
    }

    private fun createVirtualDisplay(width: Int, height: Int, density: Int, surface: Surface?, flags: Int) {
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = Unit
        }, handler)
        virtualDisplay = projection?.createVirtualDisplay("captureDisplay", width, height, density, flags, surface, null, handler)
    }

    private fun onReady(uri: Uri?) {
        if (uri == null) {
            BeagleCore.implementation.appearance.screenCaptureTexts.errorToast?.let { errorToast ->
                BeagleCore.implementation.currentActivity?.run {
                    Toast.makeText(this, text(errorToast), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            BeagleCore.implementation.currentActivity.let { currentActivity ->
                if (currentActivity == null || !currentActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    showGalleryNotification()
                } else {
                    MediaPreviewDialogFragment.show(currentActivity.supportFragmentManager, uri.path.orEmpty().replace(Regex("(/.*/)"), ""))
                }
            }
        }
        cleanUp()
        BeagleCore.implementation.onScreenCaptureReady?.invoke(uri)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showGalleryNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(GALLERY_NOTIFICATION_ID,
            NotificationCompat.Builder(this, notificationChannelId)
                .setSound(null)
                .setSmallIcon(R.drawable.beagle_ic_recording)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setDefaults(Notification.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentTitle(text(BeagleCore.implementation.appearance.screenCaptureTexts.readyNotificationTitle))
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .apply {
                    setContentIntent(
                        PendingIntent.getActivity(
                            this@ScreenCaptureService,
                            0,
                            Intent(this@ScreenCaptureService, GalleryActivity::class.java),
                            FLAG_IMMUTABLE
                        )
                    )
                    setStyle(NotificationCompat.BigTextStyle().bigText(text(BeagleCore.implementation.appearance.screenCaptureTexts.readyNotificationContent)))
                }
                .build())
    }

    companion object {
        private const val RECORDING_NOTIFICATION_ID = 2657
        private const val GALLERY_NOTIFICATION_ID = 2656
        private const val SCREENSHOT_DELAY = 300L
        private const val SCREENSHOT_TIMEOUT = 2000L
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_INTENT = "resultIntent"
        private const val EXTRA_IS_FOR_VIDEO = "isForVideo"
        private const val EXTRA_FILE_NAME = "fileName"
        private const val ACTION_DONE = "done"

        fun getStartIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            isForVideo: Boolean,
            fileName: String
        ) = Intent(context, ScreenCaptureService::class.java)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_INTENT, data)
            .putExtra(EXTRA_IS_FOR_VIDEO, isForVideo)
            .putExtra(EXTRA_FILE_NAME, fileName)
    }
}
