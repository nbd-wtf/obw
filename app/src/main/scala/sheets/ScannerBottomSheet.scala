package wtf.nbd.obw.sheets

import android.os.Bundle
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{ImageButton, TextView}
import androidx.appcompat.view.ContextThemeWrapper
import java.nio.charset.StandardCharsets
import java.nio.{ByteBuffer, ByteOrder}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.journeyapps.barcodescanner.{
  BarcodeCallback,
  BarcodeResult,
  BarcodeView
}
import com.sparrowwallet.hummingbird.registry.{CryptoAccount, CryptoHDKey}
import com.sparrowwallet.hummingbird.{UR, URDecoder}
import scodec.bits.ByteVector
import spray.json._
import scoin.DeterministicWallet._
import scoin.{ByteVector32, Protocol}
import immortan.crypto.Tools._
import immortan.utils.ImplicitJsonFormats._
import immortan.utils.InputParser

import wtf.nbd.obw.{BaseActivity, R, WalletApp}

trait HasBarcodeReader extends BarcodeCallback {
  var lastAttempt: Long = System.currentTimeMillis
  var barcodeReader: BarcodeView = _
  var instruction: TextView = _
}

trait HasUrDecoder extends HasBarcodeReader {
  val decoder: URDecoder = new URDecoder
  def onError(error: String): Unit
  def onUR(ur: UR): Unit

  def handleUR(part: String): Unit = {
    val partAdded = Try(decoder receivePart part).getOrElse(false)
    if (!partAdded && System.currentTimeMillis - lastAttempt > 2000) {
      WalletApp.app.quickToast(R.string.error_nothing_useful)
      lastAttempt = System.currentTimeMillis
    }

    val pct = decoder.getEstimatedPercentComplete
    val pctAsFractionOf100 = (pct * 100).floor.toLong
    if (pct > 0d) instruction.setText(s"$pctAsFractionOf100%")

    Option(decoder.getResult()).foreach { r =>
      if (r.error == null) {
        onUR(r.ur)
      } else {
        onError(r.error)
      }
    }
  }
}

abstract class ScannerBottomSheet(host: BaseActivity)
    extends BottomSheetDialogFragment
    with HasBarcodeReader {
  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState);
    setStyle(0, R.style.BottomSheetTheme);
  }

  def resumeBarcodeReader(): Unit =
    runAnd(barcodeReader decodeContinuous this)(barcodeReader.resume)
  def pauseBarcodeReader(): Unit =
    runAnd(barcodeReader setTorch false)(barcodeReader.pause)

  override def onDestroy(): Unit =
    runAnd(barcodeReader.stopDecoding)(super.onStop)
  override def onResume(): Unit = runAnd(resumeBarcodeReader())(super.onResume)
  override def onStop(): Unit = runAnd(pauseBarcodeReader())(super.onStop)
  var flashlight: ImageButton = _

  override def onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup,
      state: Bundle
  ): View = {
    val contextThemeWrapper = new ContextThemeWrapper(host, R.style.AppTheme)
    val inflatorExt = inflater.cloneInContext(contextThemeWrapper)
    inflatorExt.inflate(R.layout.sheet_scanner, container, false)
  }

  override def onViewCreated(view: View, savedState: Bundle): Unit = {
    instruction = view.findViewById(R.id.instruction).asInstanceOf[TextView]
    barcodeReader = view.findViewById(R.id.reader).asInstanceOf[BarcodeView]
    flashlight = view.findViewById(R.id.flashlight).asInstanceOf[ImageButton]
    flashlight setOnClickListener host.onButtonTap(toggleTorch())
  }

  def toggleTorch(): Unit = {
    val currentTag = flashlight.getTag.asInstanceOf[Int]

    if (currentTag != R.drawable.flashlight_on) {
      flashlight.setImageResource(R.drawable.flashlight_on)
      flashlight.setTag(R.drawable.flashlight_on)
      barcodeReader.setTorch(true)
    } else {
      flashlight.setImageResource(R.drawable.flashlight_off)
      flashlight.setTag(R.drawable.flashlight_off)
      barcodeReader.setTorch(false)
    }
  }
}

class OnceBottomSheet(
    host: BaseActivity,
    instructionOpt: Option[String],
    onScan: Runnable
) extends ScannerBottomSheet(host) {
  def failedScan(error: Throwable): Unit =
    WalletApp.app.quickToast(error.getMessage)
  def successfulScan(result: Any): Unit = runAnd(dismiss)(onScan.run)

  override def onViewCreated(view: View, savedState: Bundle): Unit = {
    super.onViewCreated(view, savedState)

    instructionOpt foreach { instructionText =>
      host.setVis(isVisible = true, instruction)
      instruction.setText(instructionText)
    }
  }

  override def barcodeResult(scanningResult: BarcodeResult): Unit = for {
    text <- Option(scanningResult.getText)
    if System.currentTimeMillis - lastAttempt > 2000
    _ = host.runInFutureProcessOnUI(InputParser.recordValue(text), failedScan)(
      successfulScan
    )
  } lastAttempt = System.currentTimeMillis
}

trait PairingData {
  val bip84FullPathPrefix = KeyPath(
    hardened(84L) :: hardened(0L) :: hardened(0L) :: Nil
  )
  val bip84PathPrefix = KeyPath(hardened(84L) :: hardened(0L) :: Nil)
  val masterFingerprint: Option[Long] = None
  val bip84XPub: ExtendedPublicKey
}

case class ZPubPairingData(zPubText: String) extends PairingData {
  val (_, bip84XPub) = ExtendedPublicKey.decode(zPubText, bip84PathPrefix)
}

case class HWBytesPairingData(urBytes: Array[Byte]) extends PairingData {
  val charBuffer: JsObject = StandardCharsets.UTF_8.newDecoder
    .decode(ByteBuffer wrap urBytes)
    .toString
    .parseJson
    .asJsObject
  val (_, bip84XPub) = ExtendedPublicKey.decode(
    json2String(charBuffer.fields("bip84").asJsObject fields "xpub"),
    bip84PathPrefix
  )
  val (_, masterXPub) = ExtendedPublicKey.decode(
    json2String(charBuffer fields "xpub"),
    KeyPath.Root
  )
  override val masterFingerprint: Option[Long] = Some(fingerprint(masterXPub))
}

case class HWAccountPairingData(urAccount: CryptoAccount) extends PairingData {
  private implicit def bytesToByteVector(bytes: Array[Byte]): ByteVector =
    ByteVector.view(bytes)
  private implicit def arrayToLongFingerprint(fingerPrint: Array[Byte]): Long =
    Protocol.uint32(urAccount.getMasterFingerprint, ByteOrder.BIG_ENDIAN)
  private def isBip84AccountKey(key: CryptoHDKey): Boolean =
    null != key && null != key.getOrigin && !key.isPrivateKey && KeyPath(
      key.getOrigin.getPath
    ) == bip84FullPathPrefix
  override val masterFingerprint: Option[Long] = Some(
    urAccount.getMasterFingerprint
  )

  val bip84XPub: ExtendedPublicKey = urAccount.getOutputDescriptors.asScala
    .map(_.getHdKey)
    .filter(isBip84AccountKey)
    .map { hdKey =>
      ExtendedPublicKey(
        hdKey.getKey,
        ByteVector32(hdKey.getChainCode),
        hdKey.getOrigin.getDepth,
        KeyPath(hdKey.getOrigin.getPath),
        hdKey.getParentFingerprint
      )
    }
    .head
}

class URBottomSheet(host: BaseActivity, onPairData: PairingData => Unit)
    extends ScannerBottomSheet(host)
    with HasUrDecoder {
  override def barcodeResult(res: BarcodeResult): Unit = if (
    res.getText.toLowerCase.startsWith("zpub")
  ) onZPub(res.getText)
  else handleUR(res.getText)
  override def onError(error: String): Unit = host.onFail(error)

  override def onViewCreated(view: View, savedState: Bundle): Unit = {
    super.onViewCreated(view, savedState)

    val tip = host.getString(R.string.settings_hw_zpub_tip)
    host.setVis(isVisible = true, instruction)
    instruction.setText(tip)
  }

  def onZPub(zPubText: String): Unit = {
    scala.util.Try(ZPubPairingData(zPubText)) match {
      case Failure(why)  => onError(why.getMessage)
      case Success(data) => onPairData(data)
    }

    dismiss
  }

  override def onUR(ur: UR): Unit = {
    scala.util.Try(ur.decodeFromRegistry) map {
      case urBytes: Array[Byte]     => HWBytesPairingData(urBytes)
      case urAccount: CryptoAccount => HWAccountPairingData(urAccount)
      case _ =>
        throw new RuntimeException(
          host.getString(R.string.error_nothing_useful)
        )
    } match {
      case Failure(why)  => onError(why.getMessage)
      case Success(data) => onPairData(data)
    }

    dismiss
  }
}
