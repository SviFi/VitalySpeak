package com.kafkasl.phonewhisper

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Keeps VitalySpeak working and explains it when it doesn't:
 *  - setup checks (accessibility on, mic, key, battery, notifications)
 *  - opens VitalySpeak's own accessibility page directly
 *  - a small persistent log (service start/stop, crashes, Android's recorded exit reasons)
 *  - a notification if the accessibility service gets switched off (checked after reboot,
 *    after an app update, and every few hours)
 */
object Health {
    private const val CHANNEL = "setup"
    private const val NOTIF_ID = 42
    private const val JOB_ID = 4242

    fun component(ctx: Context) = ComponentName(ctx, WhisperAccessibilityService::class.java)

    /** True if Android has VitalySpeak's accessibility service switched on (even if not bound yet). */
    fun accessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = component(ctx)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun batteryUnrestricted(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    fun notificationsAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Opens VitalySpeak's own page in Accessibility settings (Android 12+), so it's one switch
     * instead of hunting through "Installed apps". Falls back to the main Accessibility screen
     * with VitalySpeak highlighted where the phone supports that.
     */
    fun openAccessibility(ctx: Context): Boolean {
        val flat = component(ctx).flattenToString()
        val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, flat)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(direct); return true } catch (_: Exception) {}
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", flat)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", flat) })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(list); false } catch (_: Exception) { false }
    }

    fun openBatterySettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    // ---------- persistent log ----------

    private fun logFile(ctx: Context) = File(ctx.filesDir, "health.log")
    private val fmt get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun log(ctx: Context, msg: String) {
        try {
            val f = logFile(ctx)
            if (f.length() > 64_000) f.writeText(f.readLines().takeLast(200).joinToString("\n", postfix = "\n"))
            f.appendText("${fmt.format(Date())}  $msg\n")
        } catch (_: Exception) {}
    }

    fun readLog(ctx: Context): String = try { logFile(ctx).readLines().takeLast(60).reversed().joinToString("\n") } catch (_: Exception) { "" }

    /** Writes any crash to the log before the process dies (then lets Android handle it). */
    fun installCrashLogger(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(app, prev))
    }

    private class CrashLogger(val ctx: Context, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            log(ctx, "CRASH in ${t.name}: ${e.javaClass.simpleName}: ${e.message}\n" +
                e.stackTrace.take(8).joinToString("\n") { "      at $it" })
            prev?.uncaughtException(t, e)
        }
    }

    /** Android's own record of why VitalySpeak's process ended (crash, killed for memory, update…). */
    fun exitReasons(ctx: Context): String = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8).joinToString("\n") { e ->
            "${fmt.format(Date(e.timestamp))}  ${reasonName(e.reason)}" +
                (e.description?.let { " — $it" } ?: "")
        }.ifBlank { "none recorded" }
    } catch (e: Exception) { "unavailable (${e.message})" }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR (froze)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed: excessive resource use"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: low memory"
        ApplicationExitInfo.REASON_OTHER -> "other (e.g. system/vendor kill)"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by user / app update / force-stop"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        else -> "reason $r"
    }

    // ---------- setup notification ----------

    fun check(ctx: Context, source: String) {
        val on = accessibilityEnabled(ctx)
        log(ctx, "check ($source): accessibility ${if (on) "ON" else "OFF"}, battery ${if (batteryUnrestricted(ctx)) "unrestricted" else "optimized"}")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (on) { nm.cancel(NOTIF_ID); return }
        if (!notificationsAllowed(ctx)) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Setup alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("VitalySpeak is switched off")
            .setContentText("Android turned off its accessibility service. Tap to turn it back on.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /** Periodic background check (every ~6 h, also survives reboots via the boot receiver). */
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, SetupCheckJob::class.java))
            .setPeriodic(TimeUnit.HOURS.toMillis(6))
            .setPersisted(true)
            .build())
    }
}

class SetupCheckJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean { Health.check(this, "periodic"); return false }
    override fun onStopJob(params: JobParameters?) = false
}

/** After reboot or an app update: check setup, log it, re-arm the periodic check. */
class SetupCheckReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val what = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "after reboot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "after app update"
            else -> intent.action ?: "?"
        }
        Health.log(ctx, "event: $what")
        Health.schedule(ctx)
        Health.check(ctx, what)
    }
}
