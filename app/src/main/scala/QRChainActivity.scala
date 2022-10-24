package wtf.nbd.obw

import android.os.Bundle
import android.view.{View, ViewGroup}
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.mig35.carousellayoutmanager._
import com.ornach.nobobutton.NoboButton
import wtf.nbd.obw.BaseActivity.StringOps
import wtf.nbd.obw.R
import fr.acinq.bitcoin.Btc
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.electrum.ElectrumEclairWallet
import immortan.LNParams
import immortan.crypto.Tools._
import immortan.utils.{BitcoinUri, Denomination, InputParser, PaymentRequestExt}

import scala.util.chaining._

class QRChainActivity extends QRActivity with ExternalDataChecker {
  private[this] lazy val titleText =
    findViewById(R.id.titleText).asInstanceOf[TextView]
  private[this] lazy val chainQrCodes =
    findViewById(R.id.chainQrCodes).asInstanceOf[RecyclerView]
  private[this] lazy val chainQrMore =
    findViewById(R.id.chainQrMore).asInstanceOf[NoboButton]

  private[this] var wallet: ElectrumEclairWallet = _
  private[this] var allAddresses: List[BitcoinUri] = Nil
  private[this] var addresses: List[BitcoinUri] = Nil

  val adapter: RecyclerView.Adapter[QRViewHolder] =
    new RecyclerView.Adapter[QRViewHolder] {
      override def onBindViewHolder(holder: QRViewHolder, pos: Int): Unit =
        updateView(addresses(pos), holder)
      override def getItemId(itemPosition: Int): Long = itemPosition.toLong
      override def getItemCount: Int = addresses.size

      override def onCreateViewHolder(
          parent: ViewGroup,
          viewType: Int
      ): QRViewHolder = {
        val qrCodeContainer =
          getLayoutInflater.inflate(R.layout.frag_qr, parent, false)
        new QRViewHolder(qrCodeContainer)
      }

      private def updateView(bu: BitcoinUri, holder: QRViewHolder): Unit =
        bu.url.foreach { url =>
          val humanAmountOpt =
            for (requestedAmount <- bu.amount)
              yield WalletApp.denom.parsedWithSign(requestedAmount)
          val contentToShare =
            if (bu.amount.isDefined || bu.label.isDefined)
              PaymentRequestExt.withoutSlashes(InputParser.bitcoin, url)
            else bu.address

          val visibleText = (bu.label, humanAmountOpt) match {
            case Some(label) ~ Some(amount) =>
              s"${bu.address.short}<br><br>$label<br><br>$amount"
            case None ~ Some(amount) => s"${bu.address.short}<br><br>$amount"
            case Some(label) ~ None  => s"${bu.address.short}<br><br>$label"
            case _                   => bu.address.short
          }

          holder.qrLabel.setText(visibleText.html)
          runInFutureProcessOnUI(
            QRActivity.get(contentToShare, qrSize),
            onFail
          ) { qrBitmap =>
            holder.qrCopy.setOnClickListener(
              onButtonTap(
                WalletApp.app copy contentToShare
              )
            )
            holder.qrCode.setOnClickListener(
              onButtonTap(
                WalletApp.app copy contentToShare
              )
            )
            holder.qrEdit.setOnClickListener(onButtonTap(editAddress(bu)))
            holder.qrShare.setOnClickListener(onButtonTap({
              runInFutureProcessOnUI(
                shareData(qrBitmap, contentToShare),
                onFail
              )(none)
            }))
            holder.qrCode.setImageBitmap(qrBitmap)
          }
        }
    }

  def editAddress(bu: BitcoinUri): Unit = {
    val maxMsat = Btc(21000000L).toSatoshi.toMilliSatoshi
    val canReceiveFiatHuman = WalletApp.currentMsatInFiatHuman(maxMsat)
    val canReceiveHuman =
      WalletApp.denom.parsedWithSign(maxMsat)
    val body = getLayoutInflater
      .inflate(R.layout.frag_input_off_chain, null)
      .asInstanceOf[ViewGroup]
    lazy val manager = new RateManager(
      body,
      Some(getString(R.string.dialog_add_description)),
      R.string.dialog_visibility_sender,
      LNParams.fiatRates.info.rates,
      WalletApp.fiatCode
    )
    mkCheckForm(
      proceed,
      none,
      titleBodyAsViewBuilder(
        getString(R.string.dialog_receive_btc).asColoredView(
          chainWalletBackground(wallet)
        ),
        manager.content
      ),
      R.string.dialog_ok,
      R.string.dialog_cancel
    )
    manager.hintFiatDenom.setText(
      getString(R.string.dialog_up_to).format(canReceiveFiatHuman).html
    )
    manager.hintDenom.setText(
      getString(R.string.dialog_up_to).format(canReceiveHuman).html
    )
    bu.amount.foreach(manager.updateText)

    def proceed(alert: AlertDialog): Unit = {
      val resultMsat = manager.resultMsat.truncateToSatoshi.toMilliSatoshi
      val url = bu.url.get
        .removeQueryString()
        .pipe(url =>
          if (resultMsat > LNParams.chainWallets.params.dustLimit)
            url.addParam(
              "amount",
              Denomination.msat2BtcBigDecimal(resultMsat).toString
            )
          else url
        )
        .pipe(url =>
          manager.resultExtraInput match {
            case Some(resultExtraInput) =>
              url.addParam("label", resultExtraInput)
            case None => url
          }
        )

      addresses = addresses map {
        case oldUrl if oldUrl.address == bu.url.get.hostOption.get.value =>
          BitcoinUri(Some(url), oldUrl.address)
        case oldUrl =>
          BitcoinUri(
            oldUrl.url.map(_.removeQueryString()),
            oldUrl.address
          )
      }

      adapter.notifyDataSetChanged
      alert.dismiss
    }
  }

  override def PROCEED(state: Bundle): Unit = {
    setContentView(R.layout.activity_qr_chain_addresses)
    checkExternalData(noneRunnable)
  }

  override def checkExternalData(whenNone: Runnable): Unit =
    InputParser.checkAndMaybeErase {
      case chainWallet: ElectrumEclairWallet => {
        wallet = chainWallet
        runFutureProcessOnUI(wallet.getReceiveAddresses, onFail) { response =>
          titleText.setText(
            chainWalletNotice(wallet)
              .map(textRes =>
                getString(R.string.dialog_receive_btc) + "<br>" + getString(
                  textRes
                )
              )
              .getOrElse(getString(R.string.dialog_receive_btc))
              .html
          )

          val lm =
            new CarouselLayoutManager(CarouselLayoutManager.HORIZONTAL, false)
          lm.setPostLayoutListener(new CarouselZoomPostLayoutListener)
          lm.setMaxVisibleItems(5)

          allAddresses = response.keys
            .map(response.ewt.textAddress)
            .map(BitcoinUri.fromRaw)

          // start with 1
          addresses = allAddresses.take(1)

          chainQrMore.setOnClickListener(onButtonTap {
            // show 2 more
            addresses = allAddresses.take(addresses.size + 2).take(10)

            // animate list changes
            adapter.notifyItemRangeInserted(1, allAddresses.size - 1)

            // remove a button once it gets useless
            if (addresses.size == 10)
              chainQrMore.setVisibility(View.GONE)
          })

          chainQrCodes.addOnScrollListener(new CenterScrollListener)
          chainQrCodes.setLayoutManager(lm)
          chainQrCodes.setHasFixedSize(true)
          chainQrCodes.setAdapter(adapter)
        }
      }
      case _ => finish
    }
}
