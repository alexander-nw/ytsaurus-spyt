package tech.ytsaurus.spyt

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.internal.SQLConf.FILES_MAX_PARTITION_BYTES
import org.slf4j.LoggerFactory
import tech.ytsaurus.spyt.fs.YtClientConfigurationConverter.ytClientConfiguration
import tech.ytsaurus.spyt.wrapper.config.Utils.{parseRemoteConfig, remoteGlobalConfigPath, remoteVersionConfigPath}
import tech.ytsaurus.spyt.wrapper.YtJavaConverters._
import tech.ytsaurus.spyt.wrapper.YtWrapper
import tech.ytsaurus.spyt.wrapper.client.YtClientProvider

import java.util.UUID

object SessionUtils {
  private val log = LoggerFactory.getLogger(getClass)
  private val sparkDefaults = Map(
    "spark.hadoop.yt.byop.enabled" -> "true",
    "spark.hadoop.yt.read.arrow.enabled" -> "true",
    "spark.hadoop.yt.profiling.enabled" -> "false",
    "spark.hadoop.yt.mtn.enabled" -> "false",
    "spark.yt.enablers" -> Seq("byop", "read.arrow", "profiling", "mtn")
      .map(s => s"spark.hadoop.yt.$s.enabled").mkString(",")
  )

  implicit class RichSparkConf(conf: SparkConf) {
    def setEnabler(name: String, clusterConf: Map[String, String]): SparkConf = {
      val enableApp = conf.getOption(name).getOrElse(sparkDefaults(name)).toBoolean
      val enableCluster = clusterConf.get(name).exists(_.toBoolean)
      conf.set(name, (enableApp && enableCluster).toString)
    }

    def setEnablers(names: Set[String], clusterConf: Map[String, String]): SparkConf = {
      names.foldLeft(conf) { case (res, next) => res.setEnabler(next, clusterConf) }
    }

    def setAllNoOverride(settings: Map[String, String]): SparkConf = {
      settings.foldLeft(conf) { case (res, (key, value)) =>
        if (!res.contains(key)) res.set(key, value) else res
      }
    }

    def setIfEquals(key: String, expectedValue: String, newValue: String): SparkConf = {
      if (conf.getOption(key).forall(_ == expectedValue)) {
        conf.set(key, newValue)
      } else conf
    }
  }

  private def parseEnablers(conf: Map[String, String]): Set[String] = {
    conf
      .get("spark.yt.enablers")
      .map(_.split(",").map(_.trim).toSet)
      .getOrElse(Set.empty[String])
  }

  def prepareSparkConf(): SparkConf = {
    val conf = new SparkConf()
    val sparkClusterVersion = conf.get("spark.yt.cluster.version")
    val sparkClusterConfPath = conf.getOption("spark.yt.cluster.confPath")
    val id = s"tmpYtClient-${UUID.randomUUID()}"
    val yt = YtClientProvider.ytClient(ytClientConfiguration(conf), id)
    try {
      val remoteGlobalConfig = parseRemoteConfig(remoteGlobalConfigPath, yt)
      val remoteVersionConfig = parseRemoteConfig(remoteVersionConfigPath(sparkClusterVersion), yt)
      val remoteClusterConfig = sparkClusterConfPath.map(parseRemoteConfig(_, yt)).getOrElse(Map.empty[String, String])
      val enablers = parseEnablers(remoteClusterConfig).union(parseEnablers(sparkDefaults))

      conf
        .setAllNoOverride(remoteClusterConfig.filterKeys(k => !enablers.contains(k)))
        .setAllNoOverride(remoteVersionConfig)
        .setAllNoOverride(remoteGlobalConfig)
        .setEnablers(enablers, remoteClusterConfig)
        .setIfEquals(FILES_MAX_PARTITION_BYTES.key, "5000000", "512Mb") // backward compatibility of SPYT-48
    } finally {
      YtClientProvider.close(id)
    }
  }

  def buildSparkSession(sparkConf: SparkConf): SparkSession = {
    val spark = SparkSession.builder.config(sparkConf).getOrCreate()
    log.info(s"SPYT Cluster version: ${sparkConf.get("spark.yt.cluster.version")}")
    log.info(s"SPYT library version: ${sparkConf.get("spark.yt.version")}")
    spark
  }
}
