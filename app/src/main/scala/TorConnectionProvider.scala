package wtf.nbd.obw

import java.net.{InetSocketAddress, Socket}
import java.util.concurrent.TimeUnit

import android.app.ActivityManager
import android.content.{BroadcastReceiver, Context, Intent, IntentFilter}
import immortan.ConnectionProvider
import immortan.crypto.Tools._
import okhttp3.{OkHttpClient, Request}
import org.torproject.jni.TorService

import scala.jdk.CollectionConverters._

class TorConnectionProvider(context: Context) extends ConnectionProvider {
  override val proxyAddress: Option[InetSocketAddress] =
    Some(new InetSocketAddress("127.0.0.1", 9050))

  private val proxy =
    new java.net.Proxy(java.net.Proxy.Type.SOCKS, proxyAddress.get)

  val okHttpClient: OkHttpClient = (new OkHttpClient.Builder)
    .proxy(proxy)
    .connectTimeout(30, TimeUnit.SECONDS)
    .build

  override def get(url: String): String = {
    val request = (new Request.Builder).url(url).get
    okHttpClient.newCall(request.build).execute.body().string()
  }

  private val torServiceClassReference = classOf[TorService]

  def doWhenReady(action: => Unit): Unit = {
    lazy val once: BroadcastReceiver = new BroadcastReceiver {
      override def onReceive(context: Context, intent: Intent): Unit =
        if (
          intent.getStringExtra(TorService.EXTRA_STATUS) == TorService.STATUS_ON
        ) {
          try context.unregisterReceiver(once)
          catch none
          action
        }
    }

    val serviceIntent = new Intent(context, torServiceClassReference)
    val intentFilter = new IntentFilter(TorService.ACTION_STATUS)
    context.registerReceiver(once, intentFilter)
    context.startService(serviceIntent)
  }

  override def getSocket: Socket = new Socket(proxy)

  override def notifyAppAvailable(): Unit = {
    val services = context
      .getSystemService(Context.ACTIVITY_SERVICE)
      .asInstanceOf[ActivityManager]
      .getRunningServices(Integer.MAX_VALUE)
    val shouldReconnect = !services.asScala.exists(
      _.service.getClassName == torServiceClassReference.getName
    )
    if (shouldReconnect) doWhenReady(none)
  }
}
