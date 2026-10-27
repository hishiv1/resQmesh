package com.example.resqmesh

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Handles the full emergency alarm experience on the receiving device:
 * - Wakes the screen (even if locked/off)
 * - Continuous alarm sound playback
 * - Continuous vibration pattern
 * - Alarm-priority heads-up notification with sound
 * - Full-screen intent to bring the app to foreground
 */
class SosAlertManager(private val context: Context) {

    private val TAG = "ResQMesh_Alert"

    companion object {
        const val CHANNEL_ID = "resqmesh_sos_alert"
        const val NOTIFICATION_ID = 1001
    }

    private var ringtone: Ringtone? = null

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val channel = NotificationChannel(
                CHANNEL_ID,
                "SOS Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency SOS alerts from nearby devices via BLE mesh"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 200, 800, 200, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(
                    alarmSound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setBypassDnd(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Fires the full alarm sequence: wake screen → alarm sound → vibrate → heads-up notification.
     */
    fun triggerAlert(packet: PacketCache) {
        wakeScreen()
        playAlarmSound()
        vibrateDevice()
        showNotification(packet)
    }

    private fun playAlarmSound() {
        try {
            stopAlarmSound()
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val r = RingtoneManager.getRingtone(context, alarmUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                r.isLooping = true
            }
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            r.play()
            ringtone = r
        } catch (e: Exception) {
            Log.e(TAG, "Error playing alarm sound", e)
        }
    }

    private fun stopAlarmSound() {
        try {
            ringtone?.stop()
            ringtone = null
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "resqmesh:sos_wake"
            )
            wakeLock.acquire(10_000L) // Hold screen on for 10 seconds
        } catch (e: Exception) {
            Log.w(TAG, "Best-effort screen wake failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateDevice() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            // Aggressive repeating pattern: long-short-long-short
            val pattern = longArrayOf(0, 800, 200, 800, 200, 800, 200, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0)) // repeat from 0
            } else {
                vibrator.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Best-effort vibration failed: ${e.message}")
        }
    }

    private fun showNotification(packet: PacketCache) {
        val triageName = when (packet.triage_type) {
            1 -> "Medical Issue"
            2 -> "Fire Emergency"
            3 -> "Person Trapped"
            else -> "General Emergency"
        }

        // Full-screen intent brings the app to front immediately
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("\u26A0\uFE0F NEIGHBOUR EMERGENCY!")
            .setContentText("Your neighbour needs help! ($triageName)")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "\u26A0\uFE0F YOUR NEIGHBOUR NEEDS HELP!\n" +
                                "Emergency: $triageName\n" +
                                "\uD83D\uDCCD Lat: ${String.format("%.4f", packet.origin_lat)}, " +
                                "Lon: ${String.format("%.4f", packet.origin_lon)}\n" +
                                "Tap to open ResQMesh and navigate to help."
                    )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 800, 200, 800, 200, 800))
            .setSound(alarmSound)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Cancels the ongoing alarm sound, vibration, and notification.
     */
    @Suppress("DEPRECATION")
    fun cancelAlert() {
        stopAlarmSound()
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.cancel()
        } catch (_: Exception) {}

        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
