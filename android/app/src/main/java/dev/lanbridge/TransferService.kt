package dev.lanbridge

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

class TransferService : Service() {
    override fun onCreate() {
        super.onCreate()
        promote()
    }
    private fun promote() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("transfer", "局域网互传连接", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction("disconnect"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "transfer").setSmallIcon(R.drawable.ic_bridge).setContentTitle("LanBridge · 连接已开启")
            .setContentText("正在等待或进行局域网传输").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "断开连接", stop).build()).build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Also promote when an existing service receives another foreground
        // start. Connection failures cannot race this acknowledgement.
        promote()
        val engine = (application as BridgeApplication).engine
        if(intent?.action == "disconnect") engine.disconnect()
        engine.serviceReady(this, startId, intent?.getLongExtra("generation", -1L) ?: -1L)
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { (application as BridgeApplication).engine.disconnect() }
    override fun onDestroy() { (application as BridgeApplication).engine.serviceDestroyed(this); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
