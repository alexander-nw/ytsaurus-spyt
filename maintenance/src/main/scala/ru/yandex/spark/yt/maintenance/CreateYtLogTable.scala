package ru.yandex.spark.yt.maintenance

import ru.yandex.spark.yt.logger.YtDynTableLogger
import ru.yandex.spark.yt.wrapper.YtWrapper
import ru.yandex.spark.yt.wrapper.client.YtClientConfiguration
import ru.yandex.spark.yt.wrapper.table.YtTableSettings
import tech.ytsaurus.ysontree.YTreeNode

import scala.concurrent.duration._
import scala.language.postfixOps

object CreateYtLogTable extends App {
  val cluster = "hahn"
  implicit val yt = YtWrapper.createRpcClient("maintenance", YtClientConfiguration.default(cluster)).yt

  val tableSettings = new YtTableSettings {
    override def ytSchema: YTreeNode = YtDynTableLogger.logTableSchema.toYTree

    override def optionsAny: Map[String, Any] = Map("dynamic" -> true)
  }

  YtWrapper.createDir("//home/spark/logs", ignoreExisting = true)
  YtWrapper.createTable("//home/spark/logs/log_table", tableSettings)
  YtWrapper.mountTableSync("//home/spark/logs/log_table", 10 seconds)
}
