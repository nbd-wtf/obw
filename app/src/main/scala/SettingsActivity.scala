package wtf.nbd.obw

import android.content.Intent
import android.os.Bundle
import android.view.{View, ViewGroup}
import android.widget._
import wtf.nbd.obw.BaseActivity.StringOps
import wtf.nbd.obw.BuildConfig.{VERSION_CODE, VERSION_NAME}
import wtf.nbd.obw.R
import wtf.nbd.obw.sheets.{BaseChoiceBottomSheet, PairingData}
import wtf.nbd.obw.utils.{LocalBackup, OnListItemClickListener}
import com.google.android.material.snackbar.Snackbar
import com.guardanis.applock.AppLock
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.blockchain.EclairWallet._
import fr.acinq.eclair.blockchain.electrum.db.{SigningWallet, WatchingWallet}
import fr.acinq.eclair.wire.CommonCodecs.nodeaddress
import fr.acinq.eclair.wire.{Domain, NodeAddress}
import immortan.crypto.Tools._
import immortan.utils.{BtcDenomination, SatDenomination}
import immortan.{ChannelMaster, LNParams}

import scala.util.Success

abstract class SettingsHolder(host: BaseActivity) {
  lazy val view: RelativeLayout = host.getLayoutInflater
    .inflate(R.layout.frag_switch, null, false)
    .asInstanceOf[RelativeLayout]
  lazy val settingsCheck: CheckBox =
    view.findViewById(R.id.settingsCheck).asInstanceOf[CheckBox]
  lazy val settingsTitle: TextView =
    view.findViewById(R.id.settingsTitle).asInstanceOf[TextView]
  lazy val settingsInfo: TextView =
    view.findViewById(R.id.settingsInfo).asInstanceOf[TextView]
  val REQUEST_CODE_CREATE_LOCK: Int = 103
  def updateView(): Unit

  def putBoolAndUpdateView(key: String, value: Boolean): Unit = {
    WalletApp.app.prefs.edit.putBoolean(key, value).commit
    updateView()
  }
}

class SettingsActivity
    extends BaseCheckActivity
    with HasTypicalChainFee
    with ChoiceReceiver { self =>
  private[this] lazy val settingsContainer = findViewById(
    R.id.settingsContainer
  ).asInstanceOf[LinearLayout]
  private[this] lazy val titleText =
    findViewById(R.id.titleText).asInstanceOf[TextView]

  private[this] val fiatSymbols =
    LNParams.fiatRates.universallySupportedSymbols.toList.sorted
  private[this] val CHOICE_FIAT_DENOMINATION_TAG = "choiceFiatDenominationTag"
  private[this] val CHOICE_BTC_DENOMINATON_TAG = "choiceBtcDenominationTag"
  private[this] val units = List(SatDenomination, BtcDenomination)

  override def onResume(): Unit = {
    storeLocalBackup.updateView()
    if (LNParams.chainWallets.wallets.size > 1) chainWallets.updateView()
    electrum.updateView()
    setFiat.updateView()
    setBtc.updateView()

    useBiometric.updateView()
    enforceTor.updateView()
    super.onResume()
  }

  override def onChoiceMade(tag: AnyRef, pos: Int): Unit = tag match {
    case CHOICE_FIAT_DENOMINATION_TAG =>
      val fiatCode ~ _ = fiatSymbols(pos)
      WalletApp.app.prefs.edit.putString(WalletApp.FIAT_CODE, fiatCode).commit
      ChannelMaster.next(ChannelMaster.stateUpdateStream)
      setFiat.updateView()

    case CHOICE_BTC_DENOMINATON_TAG =>
      WalletApp.app.prefs.edit
        .putString(WalletApp.BTC_DENOM, units(pos).sign)
        .commit
      ChannelMaster.next(ChannelMaster.stateUpdateStream)
      setBtc.updateView()

    case _ =>
  }

  private[this] lazy val storeLocalBackup = new SettingsHolder(this) {
    setVis(isVisible = false, settingsCheck)

    def updateView(): Unit = {
      val backupAllowed = LocalBackup.isAllowed(context = WalletApp.app)
      if (backupAllowed && LNParams.cm.all.nonEmpty)
        WalletApp.backupSaveWorker.replaceWork(false)
      val title =
        if (backupAllowed) R.string.settings_backup_enabled
        else R.string.settings_backup_disabled
      val info =
        if (backupAllowed) R.string.settings_backup_where
        else R.string.settings_backup_how
      settingsTitle.setText(title)
      settingsInfo.setText(info)
    }

    view.setOnClickListener(onButtonTap {
      val intent = (new Intent).setAction(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
      )
      val intent1 = intent setData android.net.Uri.fromParts(
        "package",
        getPackageName,
        null
      )
      startActivity(intent1)
    })
  }

  private[this] lazy val chainWallets: SettingsHolder = new SettingsHolder(
    this
  ) {
    setVisMany(false -> settingsCheck, false -> settingsInfo)
    settingsTitle.setText(R.string.settings_chain_wallets)
    override def updateView(): Unit = none

    private val wallets = Map(
      BIP32 -> ("BRD, legacy wallet", "m/0'/0/n"),
      BIP44 -> ("Bitcoin.com, Mycelium, Exodus...", "m/44'/0'/0'/0/n"),
      BIP49 -> ("JoinMarket, Eclair Mobile, Pine...", "m/49'/0'/0'/0/n"),
      BIP84 -> (getString(R.string.settings_chain_modern), "m/84'/0'/0'/0/n")
    )
    val possibleKeys: List[String] = wallets.keys.toList

    view.setOnClickListener(onButtonTap {
      val options =
        for ((tag, info ~ path) <- wallets)
          yield s"<b>$tag</b> <i>$path</i><br>$info".html
      val adapter = new ArrayAdapter(
        self,
        R.layout.frag_bottomsheet_multichoice,
        options.toArray
      ) {
        override def isEnabled(itemPosition: Int): Boolean =
          itemPosition != possibleKeys.indexOf(BIP84)

        override def getView(
            itemPosition: Int,
            itemConvertedView: View,
            itemParentGroup: ViewGroup
        ): View = {
          val finalView =
            super.getView(itemPosition, itemConvertedView, itemParentGroup)
          finalView
        }
      }

      val list = selectorList(adapter)
      val listener = new OnListItemClickListener {
        def onItemClicked(itemPosition: Int): Unit = {
          val core =
            SigningWallet(possibleKeys(itemPosition), isRemovable = true)

          if (list isItemChecked itemPosition) {
            val wallet = LNParams.chainWallets.makeSigningWalletParts(
              core,
              Satoshi(0L),
              label = core.walletType
            )
            HubActivity.instance.walletCards.resetChainCards(
              LNParams.chainWallets.withFreshWallet(wallet)
            )
          } else {
            val affectedWallet = LNParams.chainWallets.wallets.find(wallet =>
              wallet.isSigning && wallet.info.core.walletType == core.walletType
            )
            affectedWallet
              .map(LNParams.chainWallets.withoutWallet)
              .foreach(HubActivity.instance.walletCards.resetChainCards)
          }
        }
      }

      list.setOnItemClickListener(listener)
      list.setChoiceMode(AbsListView.CHOICE_MODE_MULTIPLE)
      new BaseChoiceBottomSheet(list)
        .show(getSupportFragmentManager, "unused-wallet-type-tag")
      for (wallet <- LNParams.chainWallets.wallets)
        list.setItemChecked(
          possibleKeys.indexOf(wallet.info.core.walletType),
          true
        )
    })
  }

  private[this] lazy val addHardware: SettingsHolder = new SettingsHolder(
    this
  ) {
    setVisMany(false -> settingsCheck, false -> settingsInfo)
    view.setOnClickListener(onButtonTap(callUrScanner()))
    settingsTitle.setText(R.string.settings_hardware_add)
    override def updateView(): Unit = none

    def callUrScanner(): Unit = {
      def onKey(data: PairingData): Unit = {
        val (container, extraInputLayout, extraInput) = singleInputPopup
        val builder = titleBodyAsViewBuilder(
          getString(R.string.settings_hardware_label).asDefView,
          container
        )
        mkCheckForm(
          alert => runAnd(alert.dismiss)(proceed()),
          none,
          builder,
          R.string.dialog_ok,
          R.string.dialog_cancel
        )
        extraInputLayout.setHint(R.string.dialog_set_label)
        showKeys(extraInput)

        def proceed(): Unit = runAnd(finish) {
          if (
            LNParams.chainWallets.findByPubKey(data.bip84XPub.publicKey).isEmpty
          ) {
            val core = WatchingWallet(
              EclairWallet.BIP84,
              data.masterFingerprint,
              data.bip84XPub,
              isRemovable = true
            )
            val label = Some(extraInput.getText.toString.trim)
              .filter(_.nonEmpty)
              .getOrElse(EclairWallet.BIP84)
            val wallet = LNParams.chainWallets.makeWatchingWallet84Parts(
              core,
              lastBalance = Satoshi(0L),
              label
            )
            HubActivity.instance.walletCards.resetChainCards(
              LNParams.chainWallets withFreshWallet wallet
            )
          }
        }
      }

      val sheet = new sheets.URBottomSheet(self, onKey)
      callScanner(sheet)
    }
  }

  private[this] lazy val electrum: SettingsHolder = new SettingsHolder(this) {
    setVis(isVisible = false, settingsCheck)

    override def updateView(): Unit = WalletApp.customElectrumAddress match {
      case Success(nodeAddress) =>
        setTexts(
          R.string.settings_custom_electrum_enabled,
          nodeAddress.toString
        )
      case _ =>
        setTexts(
          R.string.settings_custom_electrum_disabled,
          getString(R.string.settings_custom_electrum_disabled_tip)
        )
    }

    view.setOnClickListener(onButtonTap {
      val (container, extraInputLayout, extraInput) = singleInputPopup
      val builder = titleBodyAsViewBuilder(
        getString(R.string.settings_custom_electrum_disabled).asDefView,
        container
      )
      mkCheckForm(
        alert => runAnd(alert.dismiss)(proceed()),
        none,
        builder,
        R.string.dialog_ok,
        R.string.dialog_cancel
      )
      extraInputLayout.setHint(R.string.settings_custom_electrum_host_port)
      showKeys(extraInput)

      def proceed(): Unit = {
        val input = extraInput.getText.toString.trim
        def saveAddress(address: String) = WalletApp.app.prefs.edit
          .putString(WalletApp.CUSTOM_ELECTRUM_ADDRESS, address)
        if (input.nonEmpty)
          runInFutureProcessOnUI(saveUnsafeElectrumAddress(), onFail)(_ =>
            warnAndUpdateView()
          )
        else runAnd(saveAddress(new String).commit)(warnAndUpdateView())

        def saveUnsafeElectrumAddress(): Unit = {
          val hostOrIP ~ port = input.splitAt(input lastIndexOf ':')
          val nodeAddress =
            NodeAddress.fromParts(hostOrIP, port.tail.toInt, Domain)
          saveAddress(nodeaddress.encode(nodeAddress).require.toHex).commit
        }

        def warnAndUpdateView(): Unit = {
          def onOk(snack: Snackbar): Unit =
            runAnd(snack.dismiss)(WalletApp.restart())
          val message =
            getString(R.string.settings_custom_electrum_restart_notice).html
          snack(settingsContainer, message, R.string.dialog_ok, onOk)
          updateView()
        }
      }
    })

    def setTexts(titleRes: Int, info: String): Unit = {
      settingsTitle.setText(titleRes)
      settingsInfo.setText(info)
    }
  }

  private[this] lazy val setFiat = new SettingsHolder(this) {
    settingsTitle.setText(R.string.settings_fiat_currency)
    setVis(isVisible = false, settingsCheck)

    override def updateView(): Unit =
      settingsInfo.setText(WalletApp.fiatCode.toUpperCase)

    view.setOnClickListener(onButtonTap {
      val options = fiatSymbols.map { case code ~ name =>
        code.toUpperCase + SEPARATOR + name
      }
      val list = selectorList(
        new ArrayAdapter(
          self,
          R.layout.frag_bottomsheet_item,
          options.toArray
        )
      )
      new sheets.ChoiceBottomSheet(list, CHOICE_FIAT_DENOMINATION_TAG, self)
        .show(getSupportFragmentManager, "unused-tag")
    })
  }

  private[this] lazy val setBtc = new SettingsHolder(this) {
    settingsTitle.setText(R.string.settings_btc_unit)
    setVis(isVisible = false, settingsCheck)

    view.setOnClickListener(onButtonTap {
      val options =
        for (unit <- units)
          yield unit
            .parsedWithSign(MilliSatoshi(526800020L))
            .html
      val list = selectorList(
        new ArrayAdapter(
          self,
          R.layout.frag_bottomsheet_item,
          options.toArray
        )
      )
      new sheets.ChoiceBottomSheet(list, CHOICE_BTC_DENOMINATON_TAG, self)
        .show(getSupportFragmentManager, "unused-tag")
    })

    override def updateView(): Unit = {
      val short = WalletApp.denom.sign.toUpperCase
      val isSatDenom = WalletApp.denom == SatDenomination
      val text = if (isSatDenom) s"Satoshi ($short)" else s"Bitcoin ($short)"
      settingsInfo.setText(text)
    }
  }

  private[this] lazy val useBiometric: SettingsHolder = new SettingsHolder(
    this
  ) {
    def updateView(): Unit = settingsCheck.setChecked(WalletApp.useAuth)

    view.setOnClickListener(onButtonTap {
      if (WalletApp.useAuth)
        runAnd(AppLock.getInstance(self).invalidateEnrollments)(updateView())
      else
        startActivityForResult(
          new Intent(self, ClassNames.lockCreationClass),
          REQUEST_CODE_CREATE_LOCK
        )
    })

    settingsTitle.setText(R.string.settings_use_auth)
    setVis(isVisible = false, settingsInfo)
  }

  private[this] lazy val enforceTor = new SettingsHolder(this) {
    override def updateView(): Unit =
      settingsCheck.setChecked(WalletApp.ensureTor)

    settingsTitle.setText(R.string.settings_ensure_tor)
    setVis(isVisible = false, settingsInfo)

    view.setOnClickListener(onButtonTap {
      putBoolAndUpdateView(WalletApp.ENSURE_TOR, !WalletApp.ensureTor)
      def onOk(snack: Snackbar): Unit =
        runAnd(snack.dismiss)(WalletApp.restart())
      snack(
        settingsContainer,
        getString(R.string.settings_custom_electrum_restart_notice).html,
        R.string.dialog_ok,
        onOk
      )
    })
  }

  private[this] lazy val viewCode = new SettingsHolder(this) {
    setVisMany(false -> settingsCheck, false -> settingsInfo)
    view.setOnClickListener(onButtonTap(viewRecoveryCode()))
    settingsTitle.setText(R.string.settings_view_revocery_phrase)
    override def updateView(): Unit = none
  }

  private[this] lazy val viewStat = new SettingsHolder(this) {
    setVisMany(false -> settingsCheck, false -> settingsInfo)
    view.setOnClickListener(onButtonTap(goTo(ClassNames.statActivityClass)))
    settingsTitle.setText(R.string.settings_stats)
    override def updateView(): Unit = none
  }

  override def PROCEED(state: Bundle): Unit = {
    setContentView(R.layout.activity_settings)
    titleText.setText(s"v$VERSION_NAME-$VERSION_CODE")

    val links = new TitleView("Useful links")
    addFlowChip(
      links.flow,
      getString(R.string.sources),
      R.drawable.border_purple,
      _ => browse("https://github.com/nbd-wtf/obw")
    )
    addFlowChip(
      links.flow,
      getString(R.string.twitter),
      R.drawable.border_purple,
      _ => browse("https://twitter.com/nbd_wtf")
    )

    for (count <- LNParams.logBag.count if count > 0) {
      def exportLog(): Unit =
        share(LNParams.logBag.recent.map(_.asString).mkString("\n\n"))
      val errorCount =
        s"${getString(R.string.error_log)} $count"
      addFlowChip(
        links.flow,
        errorCount,
        R.drawable.border_yellow,
        _ => exportLog()
      )
    }

    settingsContainer.addView(storeLocalBackup.view)
    if (LNParams.chainWallets.wallets.size > 1)
      settingsContainer.addView(chainWallets.view)
    settingsContainer.addView(addHardware.view)
    settingsContainer.addView(electrum.view)
    settingsContainer.addView(setFiat.view)
    settingsContainer.addView(setBtc.view)

    settingsContainer.addView(useBiometric.view)
    settingsContainer.addView(enforceTor.view)
    settingsContainer.addView(viewCode.view)
    settingsContainer.addView(viewStat.view)
    settingsContainer.addView(links.view)
  }
}
