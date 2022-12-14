package wtf.nbd.obw

import android.os.Bundle
import android.widget.{ImageButton, ImageView, RelativeLayout, TextView}
import androidx.transition.TransitionManager
import wtf.nbd.obw.BaseActivity.StringOps
import immortan.crypto.Tools._
import immortan.fsm.IncomingRevealed
import immortan.utils.{InputParser, PaymentRequestExt}
import immortan.{ChannelMaster, LNParams, PaymentInfo}
import rx.lang.scala.Subscription

class QRInvoiceActivity extends QRActivity with ExternalDataChecker {
  private[this] lazy val activityQRInvoiceMain = findViewById(
    R.id.activityQRInvoiceMain
  ).asInstanceOf[RelativeLayout]
  private[this] lazy val titleText =
    findViewById(R.id.titleText).asInstanceOf[TextView]
  private[this] lazy val invoiceHolding =
    findViewById(R.id.invoiceHolding).asInstanceOf[ImageButton]
  private[this] lazy val invoiceSuccess =
    findViewById(R.id.invoiceSuccess).asInstanceOf[ImageView]
  private[this] lazy val qrViewHolder = new QRViewHolder(
    findViewById(R.id.invoiceQr)
  )

  private var fulfillSubscription: Subscription = _
  private var holdSubscription: Subscription = _

  def markFulfilled(): Unit = UITask {
    TransitionManager.beginDelayedTransition(activityQRInvoiceMain)
    setVisMany(true -> invoiceSuccess, false -> invoiceHolding)
  }.run

  def markHolding(): Unit = UITask {
    TransitionManager.beginDelayedTransition(activityQRInvoiceMain)
    setVisMany(false -> invoiceSuccess, true -> invoiceHolding)
  }.run

  override def PROCEED(state: Bundle): Unit = {
    setContentView(R.layout.activity_qr_lightning_invoice)
    titleText.setText(getString(R.string.dialog_receive_ln).html)
    invoiceHolding.setOnClickListener(onButtonTap(finish))
    checkExternalData(noneRunnable)
  }

  def showInvoice(info: PaymentInfo): Unit =
    runInFutureProcessOnUI(
      QRActivity.get(info.prExt.raw.toUpperCase, qrSize),
      onFail
    ) { qrBitmap =>
      def share(): Unit = runInFutureProcessOnUI(
        shareData(qrBitmap, info.prExt.raw),
        onFail
      )(none)
      setVis(isVisible = false, qrViewHolder.qrEdit)

      qrViewHolder.qrLabel.setText(
        WalletApp.denom
          .parsedWithSign(info.received)
          .html
      )
      qrViewHolder.qrCopy.setOnClickListener(
        onButtonTap(WalletApp.app.copy(info.prExt.raw))
      )
      qrViewHolder.qrCode.setOnClickListener(
        onButtonTap(
          WalletApp.app.copy(info.prExt.raw)
        )
      )
      qrViewHolder.qrShare.setOnClickListener(onButtonTap(share()))
      qrViewHolder.qrCode.setImageBitmap(qrBitmap)

      fulfillSubscription = ChannelMaster.inFinalized
        .collect { case revealed: IncomingRevealed => revealed }
        .filter(revealed => info.fullTag == revealed.fullTag)
        .subscribe(_ => markFulfilled())

      holdSubscription = ChannelMaster.stateUpdateStream
        .filter { _ =>
          val incomingFsmOpt = LNParams.cm.inProcessors.get(info.fullTag)
          incomingFsmOpt.exists(info.isActivelyHolding)
        }
        .subscribe(_ => markHolding())
    }

  override def checkExternalData(whenNone: Runnable): Unit =
    InputParser.checkAndMaybeErase {
      case prEx: PaymentRequestExt =>
        LNParams.cm.payBag
          .getPaymentInfo(prEx.pr.paymentHash)
          .foreach(showInvoice)
      case _ => finish
    }

  override def onDestroy(): Unit = {
    try fulfillSubscription.unsubscribe()
    catch none
    try holdSubscription.unsubscribe()
    catch none
    super.onDestroy
  }
}
