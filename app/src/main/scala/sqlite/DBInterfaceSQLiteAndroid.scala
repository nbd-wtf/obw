package wtf.nbd.obw.sqlite

import immortan.sqlite.{DBInterface, PreparedQuery, RichCursor}
import android.database.sqlite.SQLiteDatabase

trait DBInterfaceSQLiteAndroid extends DBInterface {
  def change(sql: String, params: Array[Object]): Unit =
    base.execSQL(sql, params)

  override def change(prepared: PreparedQuery, params: Array[Object]): Unit =
    prepared.bound(params).executeUpdate()

  def select(sql: String, params: Array[String]): RichCursor = {
    val cursor = base.rawQuery(sql, params)
    RichCursorSQLiteAndroid(cursor)
  }

  def makePreparedQuery(sql: String): PreparedQuery =
    PreparedQuerySQLiteAndroid(base compileStatement sql)

  def txWrap[T](run: => T): T =
    try {
      base.beginTransaction
      val executionResult = run
      base.setTransactionSuccessful
      executionResult
    } finally {
      base.endTransaction
    }

  val base: SQLiteDatabase
}
