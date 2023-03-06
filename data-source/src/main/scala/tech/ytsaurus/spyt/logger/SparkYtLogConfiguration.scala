package tech.ytsaurus.spyt.logger

import org.apache.log4j.Level
import tech.ytsaurus.spyt.fs.conf._
import tech.ytsaurus.spyt.fs.conf.{BooleanConfigEntry, IntConfigEntry, LogLevelConfigEntry, MultiConfigEntry, StringConfigEntry}

object SparkYtLogConfiguration {
  private val prefix = "log"

  case object Enabled extends BooleanConfigEntry(s"$prefix.enabled", Some(true))

  case object Table extends StringConfigEntry(s"$prefix.table", Some("//home/spark/logs/log_table"))

  case object LogLevel extends MultiConfigEntry(prefix, "level", Some(Level.INFO),
    { (name: String, default: Option[Level]) => new LogLevelConfigEntry(name, default) })

  case object MergeExecutors extends MultiConfigEntry(prefix, "mergeExecutors", Some(true),
    { (name: String, default: Option[Boolean]) => new BooleanConfigEntry(name, default) })

  case object MaxPartitionId extends MultiConfigEntry(prefix, "maxPartitionId", Some(10),
    { (name: String, default: Option[Int]) => new IntConfigEntry(name, default) })

}
