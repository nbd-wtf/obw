package wtf.nbd.obw

import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import com.guardanis.applock.activities.{LockCreationActivity, UnlockActivity}
import immortan.LNParams
import immortan.crypto.Tools.runAnd
import immortan.utils.InputParser
import io.netty.util.internal.logging.{InternalLoggerFactory, JdkLoggerFactory}

object ClassNames {
  val lockCreationClass: Class[LockCreationActivity] =
    classOf[LockCreationActivity]
  val unlockActivityClass: Class[UnlockActivity] = classOf[UnlockActivity]

  val chanActivityClass: Class[ChanActivity] = classOf[ChanActivity]
  val statActivityClass: Class[StatActivity] = classOf[StatActivity]
  val qrSplitActivityClass: Class[QRSplitActivity] = classOf[QRSplitActivity]
  val qrChainActivityClass: Class[QRChainActivity] = classOf[QRChainActivity]
  val qrInvoiceActivityClass: Class[QRInvoiceActivity] =
    classOf[QRInvoiceActivity]
  val coinControlActivityClass: Class[CoinControlActivity] =
    classOf[CoinControlActivity]

  val settingsActivityClass: Class[SettingsActivity] = classOf[SettingsActivity]
  val remotePeerActivityClass: Class[RemotePeerActivity] =
    classOf[RemotePeerActivity]
  val mainActivityClass: Class[MainActivity] = classOf[MainActivity]
  val hubActivityClass: Class[HubActivity] = classOf[HubActivity]
}

class MainActivity extends BaseActivity {
  InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE)

  override def onResume(): Unit = runAnd(super.onResume) {
    val processIntent =
      (getIntent.getFlags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
    val dataOpt =
      Seq(getIntent.getDataString, getIntent.getStringExtra(Intent.EXTRA_TEXT))
        .find(data => null != data)
    if (processIntent)
      runInFutureProcessOnUI(
        dataOpt.foreach(InputParser.recordValue),
        _ => proceed()
      )(_ => proceed())
    else proceed()
  }

  override def START(state: Bundle): Unit = {
    setContentView(R.layout.frag_linear_layout)
    NotificationManagerCompat.from(this).cancelAll
  }

  def proceed(): Unit =
    if (LNParams.isOperational)
      exitTo(ClassNames.hubActivityClass)
    else {
      WalletApp.getMnemonics(this) match {
        case None =>
          // record is not present, this is a fresh wallet
          exitTo(classOf[SetupActivity])
        case Some(mnemonics) =>
          WalletApp.makeOperational(mnemonics)
          exitTo(ClassNames.hubActivityClass)
      }
    }
}
