package com.maqdev

import java.sql.{ResultSet, PreparedStatement, Connection, DriverManager}
import java.util.Date
import java.util.concurrent.{Callable, TimeUnit}
import com.google.common.cache.{Cache, CacheBuilder}

class SkypeDb(path: String) extends AutoCloseable {
  val db = DriverManager.getConnection("jdbc:sqlite:" + path)

  override def close() = {
    if (!db.isClosed)
      db.close()
  }

  private def fetchMessagesStmt(sinceId: Option[Int]) = {
    val params: List[(String,Int)] = List(
      sinceId.map(id ⇒ ("id > ?", id))
      //lastEditedTimestamp.map(ts ⇒ ("edited_timestamp >?", ts))
    ).flatten

    val where = params match {
      case head :: tail ⇒ " where " + head._1 + tail.map(t ⇒ " or " + t._1).mkString
      case _ ⇒ ""
    }
    val sql = "select id, convo_id, author, from_dispname, timestamp, edited_timestamp, body_xml from Messages" +
      where + " order by timestamp, id"
    //println(sql)
    val p = db.prepareStatement(sql)
    params.zipWithIndex.foreach(i ⇒ p.setInt(i._2+1, i._1._2))
    p
  }

  private def getChannelName(statement: PreparedStatement, conversationId: Int): String = {
    statement.setInt(1, conversationId)
    val rs = statement.executeQuery()
    var channelName = "--unknown--"
    if(rs.next()) {
      channelName = rs.getString("DisplayName")
    }
    channelName
  }

  def fetchMessages(sinceId: Option[Int]): Iterator[SkypeMessage] = {
    val stmt = fetchMessagesStmt(sinceId)
    val channelStmt = db.prepareStatement("select id, DisplayName from Conversations where id = ?")

    iterator(stmt.executeQuery()) map {
      rs ⇒
        val convoId = rs.getInt("convo_id")
        val channelName = SkypeDb.getOrLoad(path, convoId, () ⇒ {
          println("loading channel name #" + convoId)
          getChannelName(channelStmt, convoId)
        })

        SkypeMessage(
          rs.getInt("id"),
          rs.getString("author"),
          rs.getString("from_dispname"),
          channelName,
          convoId,
          rs.getString("body_xml"),
          new Date(1000l * rs.getInt("timestamp")),
          if (rs.getString("edited_timestamp") != null) new Date(1000l * rs.getInt("edited_timestamp")) else null
        )
    }
  }

  def iterator(rs: ResultSet): Iterator[ResultSet] = new Iterator[ResultSet] {
    def hasNext: Boolean = rs.next()
    def next(): ResultSet = rs
  }
}

object SkypeDb {
  private val cache: Cache[String, String] =
    CacheBuilder.newBuilder().expireAfterWrite(3, TimeUnit.MINUTES).build()

  def getOrLoad(path: String, id: Int, loader:() ⇒ String) = {
    val key = path + ":" + id.toString
    cache.get(key, new Loader(id, loader))
  }

  private class Loader(id: Int, loader:() ⇒ String) extends Callable[String] {
    override def call(): String = loader()
  }

}