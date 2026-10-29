package com.vozlivre.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder

/**
 * Mantém a app à escuta de chamadas com o ecrã apagado ou a app fechada.
 * Gasta bateria: só corre quando o utilizador liga "Receber chamadas em segundo plano".
 */
class ListenService : Service() {
    companion object {
        private const val CHANNEL = "vozlivre_listen"
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, ListenService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ListenService::class.java)) }
    }

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "À escuta de chamadas", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("VozLivre")
            .setContentText("À escuta de chamadas")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(3, n)

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "vozlivre:listen").apply { setReferenceCounted(false); acquire() }
            }
        } catch (_: Exception) {}

        val engine = (application as VozApp).engine
        engine.start()
        engine.startBluetooth()
        return START_STICKY
    }

    override fun onDestroy() {
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wifiLock = null
        super.onDestroy()
    }
}
