package com.polfile.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.*

object SharingState {
 val running=MutableStateFlow(false)
 val message=MutableStateFlow("آماده اشتراک‌گذاری")
 fun ip(context:Context):String? {
  val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  val preferred=cm.allNetworks.asSequence().mapNotNull { network ->
   val cap=cm.getNetworkCapabilities(network) ?: return@mapNotNull null
   if(!cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@mapNotNull null
   cm.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress && it.address.isSiteLocalAddress }?.address?.hostAddress
  }.firstOrNull()
  if(preferred!=null) return preferred
  return runCatching { NetworkInterface.getNetworkInterfaces().toList().asSequence()
   .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList().asSequence() }
   .filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress }.getOrNull()
 }
}
class ShareService:Service() {
 private var server:LocalServer?=null
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onCreate() {
  super.onCreate()
  (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
   NotificationChannel("sharing","اشتراک‌گذاری پل فایل",NotificationManager.IMPORTANCE_LOW))
 }
 private fun notification():Notification {
  val stop=PendingIntent.getService(this,1,Intent(this,ShareService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  return NotificationCompat.Builder(this,"sharing").setContentTitle("پل فایل").setContentText("سرور انتقال فایل فعال است")
   .setSmallIcon(android.R.drawable.stat_sys_upload).addAction(android.R.drawable.ic_media_pause,"توقف",stop).setOngoing(true).build()
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="stop") {stopSelf();return START_NOT_STICKY}
  if(server!=null) return START_STICKY
  try {
   ServiceCompat.startForeground(this,8080,notification(),if(Build.VERSION.SDK_INT>=29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
   server=LocalServer(applicationContext,8080).also { it.start() }
   SharingState.running.value=true;SharingState.message.value="سرور فعال است"
  } catch(e:Exception) {
   SharingState.running.value=false;SharingState.message.value="خطای سرور: " + (e.localizedMessage ?: "پورت اشغال است")
   stopSelf()
  }
  return START_STICKY
 }
 override fun onDestroy() { server?.close();server=null;SharingState.running.value=false;SharingState.message.value="سرور متوقف شد";super.onDestroy() }
}