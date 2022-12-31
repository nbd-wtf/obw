package wtf.nbd.obw

import scala.util.{Failure, Success}
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.content.Context
import android.widget.{ArrayAdapter, LinearLayout}
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.transition.TransitionManager
import wtf.nbd.obw.R
import wtf.nbd.obw.utils.LocalBackup
import com.google.common.io.ByteStreams
import com.ornach.nobobutton.NoboButton
import fr.acinq.bitcoin.MnemonicCode
import immortan.crypto.Tools.{SEPARATOR, none}
import immortan.wire.ExtCodecs.compressedByteVecCodec
import immortan.LNParams
import scodec.bits.{BitVector, ByteVector}

object SetupActivity {
  def fromMnemonics(
      context: Context,
      mnemonics: List[String],
      host: BaseActivity
  ): Unit = {
    try {
      // Implant graph into db file from resources
      val snapshotName = LocalBackup.getGraphResourceName(LNParams.chainHash)
      val compressedPlainBytes =
        ByteStreams.toByteArray(host.getAssets open snapshotName)
      val plainBytes =
        compressedByteVecCodec
          .decode(BitVector.view(compressedPlainBytes))
          .require
          .value
      LocalBackup.copyPlainDataToDbLocation(
        host,
        WalletApp.dbFileNameGraph,
        plainBytes
      )
      System.err.println("[obw][info] channels graph implanted")
    } catch {
      case err: Throwable =>
        System.err.println(s"[obw][warn] failed to implant graph: $err")
    }

    WalletApp.putMnemonics(context, mnemonics)
    WalletApp.makeOperational(mnemonics)
  }
}

class SetupActivity extends BaseActivity {
  private[this] lazy val activitySetupMain = findViewById(
    R.id.activitySetupMain
  ).asInstanceOf[LinearLayout]
  private[this] lazy val restoreOptionsButton = findViewById(
    R.id.restoreOptionsButton
  ).asInstanceOf[NoboButton]
  private[this] lazy val restoreOptions =
    findViewById(R.id.restoreOptions).asInstanceOf[LinearLayout]
  private[this] final val FILE_REQUEST_CODE = 112

  private[this] lazy val enforceTor = new SettingsHolder(this) {
    override def updateView(): Unit =
      settingsCheck.setChecked(WalletApp.ensureTor)

    settingsTitle.setText(R.string.settings_ensure_tor)
    settingsInfo.setText(R.string.setup_ensure_tor_hint)

    view.setOnClickListener(onButtonTap {
      putBoolAndUpdateView(WalletApp.ENSURE_TOR, !WalletApp.ensureTor)
    })
  }

  override def START(s: Bundle): Unit = {
    setContentView(R.layout.activity_setup)
    activitySetupMain.addView(enforceTor.view, 0)
    enforceTor.updateView()
  }

  private[this] lazy val englishWordList = {
    val rawData = getAssets.open("bip39_english_wordlist.txt")
    scala.io.Source.fromInputStream(rawData, "UTF-8").getLines().toArray
  }

  var proceedWithMnemonics: List[String] => Unit = mnemonics => {
    // Make sure this method can be run at most once (to not set runtime data twice) by replacing it with a noop method right away
    runInFutureProcessOnUI(
      SetupActivity.fromMnemonics(this, mnemonics, this),
      onFail
    )(_ => exitTo(ClassNames.hubActivityClass))
    TransitionManager.beginDelayedTransition(activitySetupMain)
    activitySetupMain.setVisibility(View.GONE)
    proceedWithMnemonics = none
  }

  override def onActivityResult(
      requestCode: Int,
      resultCode: Int,
      resultData: Intent
  ): Unit =
    if (
      requestCode == FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK && resultData != null
    ) {
      val cipherBytes = ByteStreams.toByteArray(
        getContentResolver openInputStream resultData.getData
      )

      showMnemonicPopup(R.string.action_backup_present_title) { mnemonics =>
        val seed = MnemonicCode.toSeed(mnemonics, passphrase = "")
        LocalBackup.decryptBackup(
          ByteVector.view(cipherBytes),
          seed
        ) match {

          case Success(plainEssentialBytes) =>
            // We were able to decrypt a file, implant it into db location and proceed
            LocalBackup.copyPlainDataToDbLocation(
              this,
              WalletApp.dbFileNameEssential,
              plainEssentialBytes
            )
            // Delete user-selected backup file while we can here and make an app-owned backup shortly
            DocumentFile.fromSingleUri(this, resultData.getData).delete
            WalletApp.immediatelySaveBackup()
            proceedWithMnemonics(mnemonics)

          case Failure(exc) =>
            val msg = getString(R.string.error_could_not_decrypt)
            onFail(msg.format(exc.getMessage))
        }
      }
    }

  def createNewWallet(view: View): Unit = {
    val twelveWordsEntropy: ByteVector = fr.acinq.eclair.randomBytes(16)
    val mnemonic = MnemonicCode.toMnemonics(
      twelveWordsEntropy,
      englishWordList.toIndexedSeq
    )
    proceedWithMnemonics(mnemonic)
  }

  def showRestoreOptions(view: View): Unit = {
    TransitionManager.beginDelayedTransition(activitySetupMain)
    restoreOptionsButton.setVisibility(View.GONE)
    restoreOptions.setVisibility(View.VISIBLE)
  }

  def useBackupFile(view: View): Unit = startActivityForResult(
    new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*"),
    FILE_REQUEST_CODE
  )

  def useRecoveryPhrase(view: View): Unit = showMnemonicPopup(
    R.string.action_recovery_phrase_title
  )(proceedWithMnemonics)

  def showMnemonicPopup(title: Int)(onMnemonic: List[String] => Unit): Unit = {
    val mnemonicWrap = getLayoutInflater
      .inflate(R.layout.frag_mnemonic, null)
      .asInstanceOf[LinearLayout]
    val recoveryPhrase = mnemonicWrap
      .findViewById(R.id.recoveryPhrase)
      .asInstanceOf[com.hootsuite.nachos.NachoTextView]
    recoveryPhrase.addChipTerminator(
      ' ',
      com.hootsuite.nachos.terminator.ChipTerminatorHandler.BEHAVIOR_CHIPIFY_TO_TERMINATOR
    )
    recoveryPhrase.addChipTerminator(
      ',',
      com.hootsuite.nachos.terminator.ChipTerminatorHandler.BEHAVIOR_CHIPIFY_TO_TERMINATOR
    )
    recoveryPhrase.addChipTerminator(
      '\n',
      com.hootsuite.nachos.terminator.ChipTerminatorHandler.BEHAVIOR_CHIPIFY_TO_TERMINATOR
    )
    recoveryPhrase.setAdapter(
      new ArrayAdapter(
        this,
        android.R.layout.simple_list_item_1,
        englishWordList
      )
    )
    recoveryPhrase.setDropDownBackgroundResource(R.color.button_material_dark)

    def getMnemonicList: List[String] = {
      val mnemonic = recoveryPhrase.getText.toString.toLowerCase.trim
      val pureMnemonic = mnemonic.replaceAll("[^a-zA-Z0-9']+", SEPARATOR)
      pureMnemonic.split(SEPARATOR).toList
    }

    def proceed(alert: AlertDialog): Unit = try {
      MnemonicCode.validate(
        getMnemonicList.toIndexedSeq,
        englishWordList.toIndexedSeq
      )
      onMnemonic(getMnemonicList)
      alert.dismiss
    } catch {
      case exception: Throwable =>
        val msg = getString(R.string.error_wrong_phrase)
        onFail(msg format exception.getMessage)
    }

    val builder =
      titleBodyAsViewBuilder(getString(title).asDefView, mnemonicWrap)
    val alert = mkCheckForm(
      proceed,
      none,
      builder,
      R.string.dialog_ok,
      R.string.dialog_cancel
    )
    updatePopupButton(getPositiveButton(alert), isEnabled = false)

    recoveryPhrase addTextChangedListener onTextChange { _ =>
      updatePopupButton(getPositiveButton(alert), getMnemonicList.size > 11)
    }
  }
}
