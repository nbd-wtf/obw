package wtf.nbd.obw.sqlite

import java.lang.{Double => JDouble, Integer => JInt, Long => JLong}
import immortan.sqlite.{PreparedQuery, RichCursor}
import android.database.sqlite.SQLiteStatement

case class PreparedQuerySQLiteAndroid(prepared: SQLiteStatement)
    extends PreparedQuery {

  def bound(params: Array[Object]): PreparedQuery = {
    var i = 1
    params.foreach { param =>
      param match {
        case v: JInt        => prepared.bindLong(i, v.toLong)
        case v: JDouble     => prepared.bindDouble(i, v)
        case v: String      => prepared.bindString(i, v)
        case v: Array[Byte] => prepared.bindBlob(i, v)
        case v: JLong       => prepared.bindLong(i, v)
        case _              => throw new RuntimeException
      }
      i += 1
    }
    this
  }

  def executeQuery(): RichCursor = throw new RuntimeException("Not supported")
  def executeUpdate(): Unit = prepared.executeUpdateDelete
  def close(): Unit = prepared.close
}
