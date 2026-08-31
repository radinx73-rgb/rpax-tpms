package com.rpax.tpms

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import kotlin.system.exitProcess

/**
 * Custom Application class. Installs a global uncaught-exception handler that
 * writes the full stack trace to a plain-text file in the public Downloads
 * folder (via MediaStore, no extra storage permission needed), so a crash can
 * be diagnosed without adb or a system bug report.
 *
 * The file appears as: Download/rpax_crash_<timestamp>.txt
 */
class RpaxApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(throwable)
            } catch (_: Throwable) {
                // Never let logging itself crash the crash handler.
            }
            // Preserve normal crash behavior (dialog / process death) afterwards.
            defaultHandler?.uncaughtException(thread, throwable)
                ?: exitProcess(1)
        }
    }

    private fun writeCrashLog(throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val content = buildString {
            appendLine("RPax TPMS crash report")
            appendLine("Time: $timestamp")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("App version: ${packageManager.getPackageInfo(packageName, 0).versionName}")
            appendLine()
            appendLine("--- Stack trace ---")
            append(sw.toString())
        }

        val fileName = "rpax_crash_$timestamp.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return

        resolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
