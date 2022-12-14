package wtf.nbd.obw.sqlite

import spray.json._
import immortan.utils.ImplicitJsonFormats._
import wtf.nbd.obw.sqlite.SQLiteDataExtended._
import wtf.nbd.obw.utils.{AddonData, BasicAddon, UsedAddons}
import immortan.sqlite.SQLiteData
import scala.util.Try

object SQLiteDataExtended {
  final val LABEL_ADDONS = "label-addons"

  implicit object AddonDataFmt extends JsonFormat[AddonData] {
    def write(internal: AddonData): JsValue = internal match {
      case data: BasicAddon => data.toJson
      case _                => throw new Exception
    }

    def read(raw: JsValue): AddonData = raw.asJsObject fields TAG match {
      case JsString("BasicAddon") => raw.convertTo[BasicAddon]
      case tag                    => throw new Exception(s"Unknown addon=$tag")
    }
  }

  implicit val basicAddonFmt: JsonFormat[BasicAddon] = taggedJsonFmt(
    jsonFormat[Option[String], String, String, String, BasicAddon](
      BasicAddon.apply,
      "authToken",
      "supportEmail",
      "description",
      "domain"
    ),
    tag = "BasicAddon"
  )

  implicit val usedAddonsFmt: JsonFormat[UsedAddons] =
    jsonFormat[List[AddonData], UsedAddons](UsedAddons.apply, "addons")
}

class SQLiteDataExtended(override val db: DBInterfaceSQLiteAndroidMisc)
    extends SQLiteData(db) {
  def putAddons(addons: UsedAddons): Unit =
    put(LABEL_ADDONS, addons.toJson.compactPrint getBytes "UTF-8")

  def tryGetAddons: Try[UsedAddons] =
    tryGet(LABEL_ADDONS)
      .map(bv => new String(bv.toArray, "UTF-8")) map to[UsedAddons]
}
