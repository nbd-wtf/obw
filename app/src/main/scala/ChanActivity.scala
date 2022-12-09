package wtf.nbd.obw

import java.util.{Date, TimerTask}
import scala.concurrent.duration._
import scala.util.Try
import android.graphics.{Bitmap, BitmapFactory}
import android.os.Bundle
import android.text.Spanned
import android.view.{View, ViewGroup}
import android.widget._
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import wtf.nbd.obw.BaseActivity.StringOps
import wtf.nbd.obw.R
import com.chauthai.swipereveallayout.{SwipeRevealLayout, ViewBinderHelper}
import com.google.common.cache.LoadingCache
import com.indicator.ChannelIndicatorLine
import com.ornach.nobobutton.NoboButton
import com.softwaremill.quicklens._
import rx.lang.scala.Subscription
import scodec.bits.ByteVector
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire.{HostedChannelBranding, NodeAddress}
import immortan.ChannelListener.Malfunction
import immortan._
import immortan.crypto.Tools._
import immortan.utils.{BitcoinUri, InputParser, PaymentRequestExt, Rx}
import immortan.wire.HostedState

object ChanActivity {
  def getHcState(hc: HostedCommits): String = {
    val preimages = hc.revealedFulfills.map(_.ourPreimage.toHex).mkString("\n")
    val hostedState = HostedState(
      hc.remoteInfo.nodeId,
      hc.remoteInfo.nodeSpecificPubKey,
      hc.lastCrossSignedState
    )
    val serializedHostedState = immortan.wire.ExtCodecs.hostedStateCodec
      .encode(value = hostedState)
      .require
      .toHex
    WalletApp.app
      .getString(R.string.ln_hosted_chan_state)
      .format(getDetails(hc, "n/a"), serializedHostedState, preimages)
  }

  def getDetails(cs: Commitments, fundingTxid: String): String = {
    val shortId =
      cs.updateOpt.map(_.shortChannelId.toString).getOrElse("unknown")
    val stamp =
      WalletApp.app.when(new Date(cs.startedAt), WalletApp.app.dateFormat)
    WalletApp.app
      .getString(R.string.ln_chan_details)
      .format(
        cs.remoteInfo.nodeId.toString,
        cs.remoteInfo.nodeSpecificPubKey.toString,
        shortId,
        fundingTxid,
        stamp
      )
  }
}

class ChanActivity
    extends ChanErrorHandlerActivity
    with ChoiceReceiver
    with HasTypicalChainFee
    with ChannelListener {
  private[this] lazy val chanContainer =
    findViewById(R.id.chanContainer).asInstanceOf[LinearLayout]
  private[this] lazy val getChanList =
    findViewById(R.id.getChanList).asInstanceOf[ListView]
  private[this] lazy val titleText =
    findViewById(R.id.titleText).asInstanceOf[TextView]

  private[this] lazy val brandingInfos =
    WalletApp.txDataBag.db.txWrap(getBrandingInfos.toMap)
  private[this] lazy val normalChanActions =
    getResources.getStringArray(R.array.ln_normal_chan_actions).map(_.html)
  private[this] lazy val hostedChanActions =
    getResources.getStringArray(R.array.ln_hosted_chan_actions).map(_.html)
  private[this] var updateSubscription = Option.empty[Subscription]
  private[this] var csToDisplay = Seq.empty[ChanAndCommits]

  val hcImageMemo: LoadingCache[Array[Byte], Bitmap] = memoize { bytes =>
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length)
  }

  val chanAdapter: BaseAdapter = new BaseAdapter {
    private[this] val viewBinderHelper = new ViewBinderHelper
    override def getItem(pos: Int): ChanAndCommits = csToDisplay(pos)
    override def getItemId(position: Int): Long = position.toLong
    override def getCount: Int = csToDisplay.size

    def getView(position: Int, savedView: View, parent: ViewGroup): View = {
      val card =
        if (null == savedView)
          getLayoutInflater.inflate(R.layout.frag_chan_card, null)
        else savedView

      val cardView = (getItem(position), card.getTag) match {
        case (
              ChanAndCommits(chan: ChannelHosted, hc: HostedCommits),
              view: HostedViewHolder
            ) =>
          view.fill(chan, hc)
        case (ChanAndCommits(chan: ChannelHosted, hc: HostedCommits), _) =>
          new HostedViewHolder(card).fill(chan, hc)
        case (
              ChanAndCommits(chan: ChannelNormal, commits: NormalCommits),
              view: NormalViewHolder
            ) =>
          view.fill(chan, commits)
        case (ChanAndCommits(chan: ChannelNormal, commits: NormalCommits), _) =>
          new NormalViewHolder(card).fill(chan, commits)
        case _ => throw new RuntimeException
      }

      viewBinderHelper.bind(
        cardView.swipeWrap,
        getItem(position).commits.channelId.toHex
      )
      card.setTag(cardView)
      card
    }
  }

  abstract class ChanCardViewHolder(view: View)
      extends RecyclerView.ViewHolder(view) {
    val swipeWrap: SwipeRevealLayout = itemView.asInstanceOf[SwipeRevealLayout]

    val removeItem: NoboButton =
      swipeWrap.findViewById(R.id.removeItem).asInstanceOf[NoboButton]
    val channelCard: CardView =
      swipeWrap.findViewById(R.id.channelCard).asInstanceOf[CardView]

    val hcBranding: RelativeLayout =
      swipeWrap.findViewById(R.id.hcBranding).asInstanceOf[RelativeLayout]
    val hcImageContainer: CardView =
      swipeWrap.findViewById(R.id.hcImageContainer).asInstanceOf[CardView]
    val hcImage: ImageView =
      swipeWrap.findViewById(R.id.hcImage).asInstanceOf[ImageView]
    val hcInfo: ImageView =
      swipeWrap.findViewById(R.id.hcInfo).asInstanceOf[ImageButton]

    val baseBar: ProgressBar =
      swipeWrap.findViewById(R.id.baseBar).asInstanceOf[ProgressBar]
    val overBar: ProgressBar =
      swipeWrap.findViewById(R.id.overBar).asInstanceOf[ProgressBar]
    val peerAddress: TextView =
      swipeWrap.findViewById(R.id.peerAddress).asInstanceOf[TextView]
    val chanState: View =
      swipeWrap.findViewById(R.id.chanState).asInstanceOf[View]

    val canSendText: TextView =
      swipeWrap.findViewById(R.id.canSendText).asInstanceOf[TextView]
    val canReceiveText: TextView =
      swipeWrap.findViewById(R.id.canReceiveText).asInstanceOf[TextView]
    val refundableAmountText: TextView =
      swipeWrap.findViewById(R.id.refundableAmountText).asInstanceOf[TextView]
    val paymentsInFlightText: TextView =
      swipeWrap.findViewById(R.id.paymentsInFlightText).asInstanceOf[TextView]
    val totalCapacityText: TextView =
      swipeWrap.findViewById(R.id.totalCapacityText).asInstanceOf[TextView]
    val overrideProposal: TextView =
      swipeWrap.findViewById(R.id.overrideProposal).asInstanceOf[TextView]
    val extraInfoText: TextView =
      swipeWrap.findViewById(R.id.extraInfoText).asInstanceOf[TextView]

    val wrappers: Seq[View] =
      swipeWrap.findViewById(R.id.progressBars).asInstanceOf[View] ::
        swipeWrap.findViewById(R.id.totalCapacity).asInstanceOf[View] ::
        swipeWrap.findViewById(R.id.refundableAmount).asInstanceOf[View] ::
        swipeWrap.findViewById(R.id.paymentsInFlight).asInstanceOf[View] ::
        swipeWrap.findViewById(R.id.canReceive).asInstanceOf[View] ::
        swipeWrap.findViewById(R.id.canSend).asInstanceOf[View] ::
        Nil

    def visibleExcept(goneRes: Int*): Unit = for (wrap <- wrappers) {
      val hideView = goneRes.contains(wrap.getId)
      setVis(!hideView, wrap)
    }

    baseBar.setMax(1000)
    overBar.setMax(1000)
  }

  class NormalViewHolder(view: View) extends ChanCardViewHolder(view) {
    def fill(chan: ChannelNormal, cs: NormalCommits): NormalViewHolder = {

      val capacity: Satoshi = cs.commitInput.txOut.amount
      val barCanReceive =
        (cs.availableForReceive.toLong / capacity.toLong).toInt
      val barCanSend =
        (cs.latestReducedRemoteSpec.toRemote.toLong / capacity.toLong).toInt
      val barLocalReserve =
        (cs.latestReducedRemoteSpec.toRemote - cs.availableForSend).toLong / capacity.toLong
      val tempFeeMismatch = chan.data match {
        case norm: DATA_NORMAL => norm.feeUpdateRequired
        case _                 => false
      }
      val inFlight: MilliSatoshi =
        cs.latestReducedRemoteSpec.htlcs.foldLeft(0L.msat)(_ + _.add.amountMsat)
      val refundable: MilliSatoshi =
        cs.latestReducedRemoteSpec.toRemote + inFlight

      if (Channel.isWaiting(chan)) {
        setVis(isVisible = true, extraInfoText)
        extraInfoText.setText(getString(R.string.ln_info_opening).html)
        channelCard.setOnClickListener(
          bringChanOptions(
            normalChanActions.take(2),
            cs
          )
        )
        visibleExcept(
          R.id.progressBars,
          R.id.paymentsInFlight,
          R.id.canReceive,
          R.id.canSend
        )
      } else if (Channel.isOperational(chan)) {
        channelCard.setOnClickListener(bringChanOptions(normalChanActions, cs))
        setVis(
          isVisible = cs.updateOpt.isEmpty || tempFeeMismatch,
          extraInfoText
        )
        if (cs.updateOpt.isEmpty)
          extraInfoText.setText(R.string.ln_info_no_update)
        if (tempFeeMismatch)
          extraInfoText.setText(R.string.ln_info_fee_mismatch)
        visibleExcept(goneRes = -1)
      } else {
        val closeInfoRes = chan.data match {
          case _: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT =>
            R.string.ln_info_await_close
          case close: DATA_CLOSING if close.remoteCommitPublished.nonEmpty =>
            R.string.ln_info_close_remote
          case close: DATA_CLOSING
              if close.nextRemoteCommitPublished.nonEmpty =>
            R.string.ln_info_close_remote
          case close: DATA_CLOSING
              if close.futureRemoteCommitPublished.nonEmpty =>
            R.string.ln_info_close_remote
          case close: DATA_CLOSING if close.mutualClosePublished.nonEmpty =>
            R.string.ln_info_close_coop
          case _: DATA_CLOSING => R.string.ln_info_close_local
          case _               => R.string.ln_info_shutdown
        }

        channelCard.setOnClickListener(
          bringChanOptions(
            normalChanActions.take(2),
            cs
          )
        )
        visibleExcept(R.id.progressBars, R.id.canReceive, R.id.canSend)
        extraInfoText.setText(getString(closeInfoRes).html)
        setVis(isVisible = true, extraInfoText)
      }

      removeItem.setOnClickListener(onButtonTap {
        def proceed(): Unit = chan process CMD_CLOSE(None, force = true)
        val builder = confirmationBuilder(
          cs,
          getString(R.string.confirm_ln_normal_chan_force_close).html
        )
        mkCheckForm(
          alert => runAnd(alert.dismiss)(proceed()),
          none,
          builder,
          R.string.dialog_ok,
          R.string.dialog_cancel
        )
      })

      setVis(isVisible = false, overrideProposal)
      setVis(isVisible = false, hcBranding)

      ChannelIndicatorLine.setView(chanState, chan)
      peerAddress.setText(peerInfo(cs.remoteInfo).html)
      overBar.setProgress(barCanSend min barLocalReserve.toInt)
      baseBar.setSecondaryProgress(barCanSend + barCanReceive)
      baseBar.setProgress(barCanSend)

      totalCapacityText.setText(
        sumOrNothing(capacity.toMilliSatoshi).html
      )
      canReceiveText.setText(sumOrNothing(cs.availableForReceive).html)
      canSendText.setText(sumOrNothing(cs.availableForSend).html)
      refundableAmountText.setText(sumOrNothing(refundable).html)
      paymentsInFlightText.setText(sumOrNothing(inFlight).html)
      this
    }
  }

  class HostedViewHolder(view: View) extends ChanCardViewHolder(view) {
    def fill(chan: ChannelHosted, hc: HostedCommits): HostedViewHolder = {
      val capacity =
        hc.lastCrossSignedState.initHostedChannel.channelCapacityMsat
      val inFlight =
        hc.nextLocalSpec.htlcs.foldLeft(0L.msat)(_ + _.add.amountMsat)
      val barCanReceive =
        (hc.availableForReceive.toLong / capacity.truncateToSatoshi.toLong).toInt
      val barCanSend =
        (hc.availableForSend.toLong / capacity.truncateToSatoshi.toLong).toInt

      val errorText = (hc.localError, hc.remoteError) match {
        case Some(error) ~ _ => s"LOCAL: ${ErrorExt extractDescription error}"
        case _ ~ Some(error) => s"REMOTE: ${ErrorExt extractDescription error}"
        case _               => new String
      }

      val brandOpt = brandingInfos.get(hc.remoteInfo.nodeId)

      // Hide image container at start, show it later if bitmap is fine
      hcInfo.setOnClickListener(
        onButtonTap(
          browse(
            "https://github.com/lightning/blips/blob/master/blip-0017.md"
          )
        )
      )
      setVisMany(
        true -> hcBranding,
        false -> hcImageContainer,
        hc.overrideProposal.isDefined -> overrideProposal
      )

      for {
        HostedChannelBranding(_, pngIcon, contactInfo) <- brandOpt
        bitmapImage <- Try(pngIcon.get.toArray).map(hcImageMemo.get)
        _ = hcImage.setOnClickListener(onButtonTap(browse(contactInfo)))
        _ = setVis(isVisible = true, hcImageContainer)
      } hcImage.setImageBitmap(bitmapImage)

      removeItem setOnClickListener onButtonTap {
        if (hc.localSpec.htlcs.nonEmpty)
          snack(
            chanContainer,
            getString(R.string.ln_hosted_chan_remove_impossible).html,
            R.string.dialog_ok,
            _.dismiss
          )
        else
          mkCheckForm(
            alert => runAnd(alert.dismiss)(removeHc(hc)),
            none,
            confirmationBuilder(
              hc,
              getString(R.string.confirm_ln_hosted_chan_remove).html
            ),
            R.string.dialog_ok,
            R.string.dialog_cancel
          )
      }

      overrideProposal setOnClickListener onButtonTap {
        val newBalance =
          hc.lastCrossSignedState.initHostedChannel.channelCapacityMsat - hc.overrideProposal.get.localBalanceMsat
        val current =
          WalletApp.denom.parsedWithSign(hc.availableForSend)
        val overridden =
          WalletApp.denom.parsedWithSign(newBalance)

        def proceed(): Unit = chan.acceptOverride() match {
          case Left(err) => chanError(hc.channelId, err, hc.remoteInfo)
          case _         => {}
        }

        val builder = confirmationBuilder(
          hc,
          getString(R.string.ln_hc_override_warn)
            .format(current, overridden)
            .html
        )
        mkCheckForm(
          alert => runAnd(alert.dismiss)(proceed()),
          none,
          builder,
          R.string.dialog_ok,
          R.string.dialog_cancel
        )
      }

      channelCard.setOnClickListener(bringChanOptions(hostedChanActions, hc))

      visibleExcept(R.id.refundableAmount)
      ChannelIndicatorLine.setView(chanState, chan)
      peerAddress.setText(peerInfo(hc.remoteInfo).html)
      baseBar.setSecondaryProgress(barCanSend + barCanReceive)
      baseBar.setProgress(barCanSend)

      totalCapacityText.setText(sumOrNothing(capacity).html)
      canReceiveText.setText(sumOrNothing(hc.availableForReceive).html)
      canSendText.setText(sumOrNothing(hc.availableForSend).html)
      paymentsInFlightText.setText(sumOrNothing(inFlight).html)

      // Order messages by degree of importance since user can only see a single one
      setVis(
        isVisible = hc.error.isDefined || hc.updateOpt.isEmpty,
        extraInfoText
      )
      extraInfoText.setText(R.string.ln_info_no_update)
      extraInfoText.setText(errorText)
      this
    }
  }

  override def onDestroy(): Unit = {
    try LNParams.cm.all.values.foreach(_.listeners -= this)
    catch none
    updateSubscription.foreach(_.unsubscribe())
    super.onDestroy
  }

  override def onChoiceMade(tag: AnyRef, pos: Int): Unit = (tag, pos) match {
    case (cs: NormalCommits, 0) =>
      share(
        ChanActivity.getDetails(
          cs,
          cs.commitInput.outPoint.txid.toString
        )
      )
    case (nc: HostedCommits, 0) =>
      share(ChanActivity.getDetails(nc, fundingTxid = "n/a"))
    case (hc: HostedCommits, 1) => share(ChanActivity.getHcState(hc))
    case (cs: NormalCommits, 1) => closeNcToWallet(cs)

    case (hc: HostedCommits, 2) =>
      val builder =
        confirmationBuilder(
          hc,
          getString(R.string.confirm_ln_hosted_chan_drain).html
        )
      mkCheckForm(
        alert => runAnd(alert.dismiss)(drainHc(hc)),
        none,
        builder,
        R.string.dialog_ok,
        R.string.dialog_cancel
      )

    case (cs: NormalCommits, 2) => closeNcToAddress(cs)
    case (cs: Commitments, 3)   => receiveIntoChan(cs)
    case _                      =>
  }

  override def onException: PartialFunction[Malfunction, Unit] = {
    case (CMDException(reason, _: CMD_CLOSE), _, data: HasNormalCommitments) =>
      chanError(data.channelId, reason, data.commitments.remoteInfo)
  }

  def closeNcToWallet(cs: NormalCommits): Unit = {
    bringChainWalletChooser(normalChanActions.tail.head.toString) { wallet =>
      runFutureProcessOnUI(wallet.getReceiveAddresses, onFail) {
        addressResponse =>
          val pubKeyScript =
            LNParams.addressToPubKeyScript(addressResponse.firstAccountAddress)
          for (chan <- getChanByCommits(cs))
            chan process CMD_CLOSE(Some(pubKeyScript), force = false)
      }
    }
  }

  def closeNcToAddress(cs: NormalCommits): Unit = {
    def confirmResolve(bitcoinUri: BitcoinUri): Unit = {
      val pubKeyScript = LNParams.addressToPubKeyScript(bitcoinUri.address)
      val builder = confirmationBuilder(
        cs,
        getString(R.string.confirm_ln_normal_chan_close_address)
          .format(bitcoinUri.address.humanFour)
          .html
      )
      def proceed(): Unit = for (chan <- getChanByCommits(cs))
        chan process CMD_CLOSE(Some(pubKeyScript), force = false)
      mkCheckForm(
        alert => runAnd(alert.dismiss)(proceed()),
        none,
        builder,
        R.string.dialog_ok,
        R.string.dialog_cancel
      )
    }

    def resolveClosingAddress(): Unit = InputParser.checkAndMaybeErase {
      case ext: PaymentRequestExt if ext.pr.fallbackAddress().isDefined =>
        ext.pr.fallbackAddress().map(BitcoinUri.fromRaw).foreach(confirmResolve)
      case bitcoinUri: BitcoinUri
          if Try(LNParams addressToPubKeyScript bitcoinUri.address).isSuccess =>
        confirmResolve(bitcoinUri)
      case _ => nothingUsefulTask.run
    }

    def onData: Runnable = UITask(resolveClosingAddress())
    val sheet =
      new sheets.OnceBottomSheet(
        this,
        Some(getString(R.string.scan_btc_address)),
        onData
      )
    callScanner(sheet)
  }

  def drainHc(hc: HostedCommits): Unit = {
    val relatedHc = getChanByCommits(hc).toList
    val maxSendable = LNParams.cm.maxSendable(relatedHc)
    val preimage = randomBytes32

    LNParams.cm.maxReceivable(
      LNParams.cm.sortedReceivable(
        LNParams.cm.all.filter(_._1 != hc.channelId).values
      )
    ) match {
      case _ if maxSendable < LNParams.minPayment =>
        snack(
          chanContainer,
          getString(R.string.ln_hosted_chan_drain_impossible_few_funds).html,
          R.string.dialog_ok,
          _.dismiss
        )
      case Some(csAndMax) if csAndMax.maxReceivable < LNParams.minPayment =>
        snack(
          chanContainer,
          getString(R.string.ln_hosted_chan_drain_impossible_no_chans).html,
          R.string.dialog_ok,
          _.dismiss
        )
      case Some(csAndMax) =>
        val toSend = maxSendable.min(csAndMax.maxReceivable)
        val pd = PaymentDescription(
          split = None,
          label = Some(getString(R.string.tx_ln_label_reflexive)),
          semanticOrder = None,
          invoiceText = new String,
          toSelfPreimage = Some(preimage)
        )
        val prExt = LNParams.cm.makePrExt(
          toReceive = toSend,
          description = pd,
          allowedChans = csAndMax.commits,
          hash = Crypto.sha256(preimage),
          secret = randomBytes32
        )
        val cmd = LNParams.cm
          .makeSendCmd(
            prExt,
            allowedChans = relatedHc,
            LNParams.cm.feeReserve(toSend),
            toSend
          )
          .modify(_.split.totalSum)
          .setTo(toSend)
        WalletApp.app.quickToast(
          getString(R.string.dialog_lnurl_processing)
            .format(getString(R.string.tx_ln_label_reflexive))
            .html
        )
        replaceOutgoingPayment(
          prExt,
          pd,
          action = None,
          sentAmount = prExt.pr.amountOpt.get
        )
        LNParams.cm.localSend(cmd)
      case None => {}
    }
  }

  def receiveIntoChan(commits: Commitments): Unit = {
    lnReceiveGuard(getChanByCommits(commits).toList, chanContainer) {
      new OffChainReceiver(
        getChanByCommits(commits).toList,
        initMaxReceivable = Long.MaxValue.msat,
        initMinReceivable = 0L.msat
      ) {
        override def getManager: RateManager = new RateManager(
          body,
          Some(getString(R.string.dialog_add_description)),
          R.string.dialog_visibility_sender,
          LNParams.fiatRates.info.rates,
          WalletApp.fiatCode
        )
        override def getDescription: PaymentDescription = PaymentDescription(
          split = None,
          label = None,
          semanticOrder = None,
          invoiceText = manager.resultExtraInput getOrElse new String
        )
        override def processInvoice(prExt: PaymentRequestExt): Unit =
          goToWithValue(ClassNames.qrInvoiceActivityClass, prExt)
        override def getTitleText: String =
          getString(R.string.dialog_receive_ln)
      }
    }
  }

  def removeHc(hc: HostedCommits): Unit = {
    LNParams.cm.chanBag.delete(hc.channelId)
    LNParams.cm.all -= hc.channelId

    // Update hub activity balance and chan list here
    ChannelMaster.next(ChannelMaster.stateUpdateStream)
    CommsTower.disconnectNative(hc.remoteInfo)
    updateChanData.run
  }

  def resolveNodeQr(): Unit = InputParser.checkAndMaybeErase {
    case _: RemoteNodeInfo => exitTo(ClassNames.remotePeerActivityClass)
    case _                 => nothingUsefulTask.run
  }

  def scanNodeQr(): Unit = {
    def onData: Runnable = UITask(resolveNodeQr())
    val sheet =
      new sheets.OnceBottomSheet(
        this,
        Some(getString(R.string.chan_open_scan)),
        onData
      )
    callScanner(sheet)
  }

  def pasteNodeQr(): Unit = {
    val (container, extraInputLayout, extraInput) = singleInputPopup

    def proceed(alert: AlertDialog): Unit = runAnd(alert.dismiss) {
      runInFutureProcessOnUI(
        InputParser.recordValue(extraInput.getText.toString),
        onFail
      ) { _ => UITask(resolveNodeQr()).run() }
    }

    val builder = titleBodyAsViewBuilder(title = null, body = container)
    def switchToScanner(alert: AlertDialog): Unit =
      runAnd(alert.dismiss)(scanNodeQr())

    mkCheckFormNeutral(
      proceed,
      none,
      switchToScanner,
      builder,
      R.string.dialog_ok,
      R.string.dialog_cancel,
      R.string.dialog_scan
    )
    extraInputLayout.setHint(
      getString(R.string.chan_open_paste) ++ " (nodeid@host:port)"
    )
    showKeys(extraInput)
  }

  override def PROCEED(state: Bundle): Unit = {
    for (channel <- LNParams.cm.all.values) channel.listeners += this
    setContentView(R.layout.activity_chan)
    updateChanData.run

    titleText.setText(getString(R.string.title_chans))

    val scanFooter = new TitleView(getString(R.string.chan_open))
    val lspFooter = new TitleView(getString(R.string.chan_lsp_list_title))
    val hcpFooter = new TitleView(getString(R.string.chan_hcp_list_title))
    val mfnFooter = new TitleView(getString(R.string.chan_mfn_list_title))

    addFlowChip(
      scanFooter.flow,
      getString(R.string.chan_open_scan),
      R.drawable.border_purple,
      _ => scanNodeQr()
    )

    addFlowChip(
      scanFooter.flow,
      getString(R.string.chan_open_paste),
      R.drawable.border_basic,
      _ => pasteNodeQr()
    )

    if (LNParams.isMainnet) {
      addFlowChip(
        lspFooter.flow,
        "LNBIG",
        R.drawable.border_basic,
        _ => browse("https://lnbig.com/#/open-channel")
      )
      addFlowChip(
        lspFooter.flow,
        "BlockTank",
        R.drawable.border_basic,
        _ => browse("https://synonym.to/blocktank/")
      )

      addFlowChip(
        hcpFooter.flow,
        "ergvein.net",
        R.drawable.border_basic,
        _ =>
          goToWithValue(
            ClassNames.remotePeerActivityClass,
            LNParams.syncParams.ergveinNet
          )
      )

      addFlowChip(
        hcpFooter.flow,
        "SATM",
        R.drawable.border_basic,
        _ =>
          goToWithValue(
            ClassNames.remotePeerActivityClass,
            LNParams.syncParams.satm
          )
      )

      addFlowChip(
        hcpFooter.flow,
        "Jiraiya",
        R.drawable.border_basic,
        _ =>
          goToWithValue(
            ClassNames.remotePeerActivityClass,
            LNParams.syncParams.jiraiya
          )
      )

      // list taken from https://github.com/hsjoberg/blixt-wallet/issues/1033
      List(
        RemoteNodeInfo(
          Crypto.PublicKey(
            ByteVector.fromValidHex(
              "024bfaf0cabe7f874fd33ebf7c6f4e5385971fc504ef3f492432e9e3ec77e1b5cf"
            )
          ),
          NodeAddress.fromParts("52.1.72.207", 9735),
          "deezy.io"
        ),
        RemoteNodeInfo(
          Crypto.PublicKey(
            ByteVector.fromValidHex(
              "0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3"
            )
          ),
          NodeAddress.fromParts("3.124.63.44", 9735),
          "CoinGate"
        ),
        RemoteNodeInfo(
          Crypto.PublicKey(
            ByteVector.fromValidHex(
              "03e81689bfd18d0accb28d720ed222209b1a5f2c6825308772beac75b1fe35d491"
            )
          ),
          NodeAddress.fromParts("46.105.76.211", 9735),
          "Rust-eze"
        ),
        RemoteNodeInfo(
          Crypto.PublicKey(
            ByteVector.fromValidHex(
              "0230a5bca558e6741460c13dd34e636da28e52afd91cf93db87ed1b0392a7466eb"
            )
          ),
          NodeAddress.fromParts("176.9.17.121", 9735),
          "Blixt"
        ),
        RemoteNodeInfo(
          Crypto.PublicKey(
            ByteVector.fromValidHex(
              "03c72f89b660de43fc5c77ef879cbf7846601af88befb80e436242909b14fd0495"
            )
          ),
          NodeAddress.fromParts("47.40.121.33", 9735),
          "RecklessApotheosis"
        ),
        RemoteNodeInfo(
          Crypto.PublicKey(
            ByteVector.fromValidHex(
              "03bb88ccc444534da7b5b64b4f7b15e1eccb18e102db0e400d4b9cfe93763aa26d"
            )
          ),
          NodeAddress.fromParts("138.68.14.104", 9735),
          "ln2me.com"
        )
      ).foreach { n =>
        addFlowChip(
          mfnFooter.flow,
          n.alias,
          R.drawable.border_basic,
          _ => goToWithValue(ClassNames.remotePeerActivityClass, n)
        )
      }
    }

    getChanList.addFooterView(scanFooter.view)
    getChanList.addFooterView(lspFooter.view)
    getChanList.addFooterView(hcpFooter.view)
    getChanList.addFooterView(mfnFooter.view)
    getChanList.setAdapter(chanAdapter)
    getChanList.setDividerHeight(0)
    getChanList.setDivider(null)

    val window = 500.millis
    val stateEvents = Rx.uniqueFirstAndLastWithinWindow(
      ChannelMaster.stateUpdateStream,
      window
    )
    val statusEvents = Rx.uniqueFirstAndLastWithinWindow(
      ChannelMaster.statusUpdateStream,
      window
    )
    updateSubscription = Some(
      stateEvents
        .merge(statusEvents)
        .subscribe(_ => updateChanData.run)
    )
  }

  private def getBrandingInfos = for {
    ChanAndCommits(_: ChannelHosted, commits) <- csToDisplay
    brand <- WalletApp.extDataBag
      .tryGetBranding(commits.remoteInfo.nodeId)
      .toOption
  } yield commits.remoteInfo.nodeId -> brand

  private def sumOrNothing(amt: MilliSatoshi): String = {
    if (0L.msat != amt) WalletApp.denom.parsedWithSign(amt)
    else getString(R.string.chan_nothing)
  }

  private def peerInfo(info: RemoteNodeInfo): String =
    s"<strong>${info.alias}</strong><br>${info.address.toString}"

  private def confirmationBuilder(commits: Commitments, msg: CharSequence) =
    new AlertDialog.Builder(this, R.style.DialogTheme)
      .setTitle(commits.remoteInfo.address.toString)
      .setMessage(msg)

  private def getChanByCommits(commits: Commitments) =
    csToDisplay.collectFirst {
      case cnc if cnc.commits.channelId == commits.channelId => cnc.chan
    }

  private def updateChanData: TimerTask = UITask {
    csToDisplay =
      LNParams.cm.all.values.flatMap(Channel.chanAndCommitsOpt).toList
    chanAdapter.notifyDataSetChanged
  }

  def bringChanOptions(
      options: Array[Spanned],
      cs: Commitments
  ): View.OnClickListener = onButtonTap {
    val list = selectorList(
      new ArrayAdapter(
        this,
        R.layout.frag_bottomsheet_item,
        options
      )
    )
    new sheets.ChoiceBottomSheet(list, cs, this)
      .show(getSupportFragmentManager, "unused-tag")
  }
}
