package wtf.nbd.obw.utils

import okhttp3.{OkHttpClient, Request}
import java.net.{InetSocketAddress, Socket}
import java.util.concurrent.TimeUnit
import immortan.ConnectionProvider

class OkHttpConnectionProvider extends ConnectionProvider {
  private val okHttpClient: OkHttpClient =
    (new OkHttpClient.Builder).connectTimeout(15, TimeUnit.SECONDS).build

  def doWhenReady(action: => Unit): Unit = action
  val proxyAddress: Option[InetSocketAddress] = Option.empty
  def getSocket: Socket = new Socket
  def get(url: String): String = {
    val request = (new Request.Builder).url(url).get()
    okHttpClient.newCall(request.build).execute.body().string()
  }
}
