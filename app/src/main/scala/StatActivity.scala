package wtf.nbd.obw

import android.os.Bundle
import android.widget.{TextView, LinearLayout}
import scoin.ln._
import immortan.LNParams

import wtf.nbd.obw.R

class StatActivity extends BaseCheckActivity {
  private[this] lazy val titleText =
    findViewById(R.id.titleText).asInstanceOf[TextView]
  private[this] lazy val statContainer =
    findViewById(R.id.settingsContainer).asInstanceOf[LinearLayout]

  override def PROCEED(state: Bundle): Unit = {
    setContentView(R.layout.activity_settings)
    titleText.setText(getString(R.string.settings_stats))
    updateView()
  }

  def updateView(): Unit = {
    WalletApp.txDataBag.db.txWrap {
      val txSummary = WalletApp.txDataBag.txSummary.filter(_.count > 0)
      val relaySummary = LNParams.cm.payBag.relaySummary.filter(_.count > 0)
      val paymentSummary = LNParams.cm.payBag.paymentSummary.filter(_.count > 0)
      val channelTxFeesSummary =
        LNParams.cm.chanBag.channelTxFeesSummary.filter(_.count > 0)

      for (summary <- txSummary) {
        val slotTitle = new TitleView(getString(R.string.stats_title_chain))
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_transactions).format(summary.count),
          R.drawable.border_basic
        )
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_received).format(
            WalletApp.denom
              .directedWithSign(
                summary.received.toMilliSatoshi,
                0L.msat,
                isIncoming = true
              )
          ),
          R.drawable.border_basic
        )
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_sent).format(
            WalletApp.denom
              .directedWithSign(
                0L.msat,
                summary.sent.toMilliSatoshi,
                isIncoming = false
              )
          ),
          R.drawable.border_basic
        )
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_fees).format(
            WalletApp.denom
              .directedWithSign(
                0L.msat,
                summary.fees.toMilliSatoshi,
                isIncoming = false
              )
          ),
          R.drawable.border_basic
        )
        statContainer.addView(slotTitle.view)
      }

      for (summary <- paymentSummary) {
        val slotTitle = new TitleView(getString(R.string.stats_title_ln))
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_payments).format(summary.count),
          R.drawable.border_basic
        )
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_received).format(
            WalletApp.denom
              .directedWithSign(
                summary.received,
                0L.msat,
                isIncoming = true
              )
          ),
          R.drawable.border_basic
        )
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_sent).format(
            WalletApp.denom
              .directedWithSign(
                0L.msat,
                summary.sent,
                isIncoming = false
              )
          ),
          R.drawable.border_basic
        )
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_fees).format(
            WalletApp.denom
              .directedWithSign(
                0L.msat,
                summary.fees,
                isIncoming = false
              )
          ),
          R.drawable.border_basic
        )
        val feesSaved = WalletApp.denom.directedWithSign(
          summary.chainFees - summary.fees,
          0L.msat,
          summary.chainFees > summary.fees
        )
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_fees_saved).format(feesSaved),
          R.drawable.border_basic
        )
        statContainer.addView(slotTitle.view)
      }

      for (summary <- relaySummary) {
        val slotTitle = new TitleView(getString(R.string.stats_title_relays))
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_relays).format(summary.count),
          R.drawable.border_basic
        )
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_relayed).format(
            WalletApp.denom
              .parsedWithSign(summary.relayed)
          ),
          R.drawable.border_basic
        )
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_earned).format(
            WalletApp.denom
              .directedWithSign(
                summary.earned,
                0L.msat,
                isIncoming = true
              )
          ),
          R.drawable.border_basic
        )
        statContainer.addView(slotTitle.view)
      }

      for (summary <- channelTxFeesSummary) {
        val slotTitle =
          new TitleView(getString(R.string.stats_title_chan_loss))
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_transactions).format(summary.count),
          R.drawable.border_basic
        )
        addFlowChip(
          slotTitle.flow,
          getString(R.string.stats_item_fees).format(
            WalletApp.denom
              .directedWithSign(
                0L.msat,
                summary.fees.toMilliSatoshi,
                isIncoming = false
              )
          ),
          R.drawable.border_basic
        )
        statContainer.addView(slotTitle.view)
      }
    }
  }
}
