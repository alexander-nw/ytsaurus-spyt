package ru.yandex.spark.yt.wrapper.discovery

import org.slf4j.LoggerFactory
import ru.yandex.spark.HostAndPort
import ru.yandex.spark.yt.wrapper.YtWrapper
import ru.yandex.spark.yt.wrapper.operation.OperationStatus
import tech.ytsaurus.client.CompoundClient
import tech.ytsaurus.client.request.GetOperation
import tech.ytsaurus.core.GUID
import tech.ytsaurus.ysontree.YTreeNode

import java.util.Optional
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}
import scala.util.{Failure, Success, Try}

class CypressDiscoveryService(discoveryPath: String)(implicit yt: CompoundClient) extends DiscoveryService {
  private val log = LoggerFactory.getLogger(getClass)

  private def addressPath: String = s"$discoveryPath/spark_address"

  private def webUiPath: String = s"$discoveryPath/webui"

  private def restPath: String = s"$discoveryPath/rest"

  private def operationPath: String = s"$discoveryPath/operation"

  private def childrenOperationsPath: String = s"$discoveryPath/children_operations"

  private def shsPath: String = s"$discoveryPath/shs"

  private def clusterVersionPath: String = s"$discoveryPath/version"

  private def confPath: String = s"$discoveryPath/conf"

  private def masterWrapperPath: String = s"$discoveryPath/master_wrapper"

  override def operationInfo: Option[OperationInfo] = operation.flatMap(oid => {
    val id = GUID.valueOf(oid)
    val r = yt.getOperation(new GetOperation(id)).join()
    if (r.getAttribute("state").isPresent) {
      Try(OperationStatus.getByName(r.getAttribute("state").get().stringValue()))
        .toOption
        .map(s => OperationInfo(id, s))
    } else None
  })

  override def registerMaster(operationId: String,
                              address: Address,
                              clusterVersion: String,
                              masterWrapperEndpoint: HostAndPort,
                              clusterConf: SparkConfYsonable): Unit = {
    val clearDir = discoverAddress() match {
      case Success(_) if operation.exists(_ != operationId) && operationInfo.exists(!_.state.isFinished) =>
        throw new IllegalStateException(s"Spark instance with path $discoveryPath already exists")
      case Success(_) =>
        log.info(s"Spark instance with path $discoveryPath registered, but is not alive, rewriting id")
        true
      case Failure(EmptyDirectoryException(_)) =>
        log.info(s"Spark instance with path $discoveryPath doesn't exist, registering new one")
        false
      case Failure(ex) =>
        throw ex
    }

    val transaction = YtWrapper.createTransaction(None, 1 minute)
    val tr = Some(transaction.getId.toString)
    try {
      if (clearDir) removeAddress(tr)
      YtWrapper.createDir(s"$addressPath/${YtWrapper.escape(address.hostAndPort.toString)}", tr)
      Map(
        webUiPath -> YtWrapper.escape(address.webUiHostAndPort.toString),
        restPath -> YtWrapper.escape(address.restHostAndPort.toString),
        operationPath -> operationId,
        clusterVersionPath -> clusterVersion,
        masterWrapperPath -> YtWrapper.escape(masterWrapperEndpoint.toString)
      ).foreach { case (path, value) =>
        YtWrapper.createDir(s"$path/$value", tr)
      }
      YtWrapper.createDocumentFromProduct(confPath, clusterConf, tr)
    } catch {
      case e: Throwable =>
        transaction.abort().join()
        throw e
    }
    transaction.commit().join()
  }

  override def registerWorker(operationId: String): Unit = {
    log.info(s"Registering worker operation $operationId")
    if (!operation.contains(operationId) && operationId != null && !operationId.isBlank) {
      log.info(s"Registering worker operation $operationId: started")
      val tr = YtWrapper.createTransaction(None, 1 minute)
      YtWrapper.createDir(s"$childrenOperationsPath/$operationId", Some(tr.getId.toString), ignoreExisting = true)
      tr.commit().join()
      log.info(s"Registering worker operation $operationId: completed")
    }
  }


  override def registerSHS(address: HostAndPort): Unit = {
    val transaction = YtWrapper.createTransaction(None, 1 minute)
    val tr = Some(transaction.getId.toString)
    val addr = YtWrapper.escape(address.toString)
    YtWrapper.removeDirIfExists(shsPath, recursive = true, tr)
    YtWrapper.createDir(s"$shsPath/$addr", tr)
    transaction.commit().join()
  }

  private def cypressHostAndPort(path: String): Try[HostAndPort] = {
    getPath(path).map(HostAndPort.fromString)
  }

  private def getPath(path: String): Try[String] =
    if (YtWrapper.exists(path))
      Try(YtWrapper.listDir(path)).map(_.head)
    else
      Failure(EmptyDirectoryException(s"Path not found: $path"))


  override def discoverAddress(): Try[Address] =
    for {
      _ <- getPath(confPath).recover { case InvalidCatalogException(msg) => EmptyDirectoryException(msg) }
      hostAndPort <- cypressHostAndPort(addressPath)
      webUiHostAndPort <- cypressHostAndPort(webUiPath)
      restHostAndPort <- cypressHostAndPort(restPath)
    } yield Address(hostAndPort, webUiHostAndPort, restHostAndPort)

  def clusterVersion: Option[String] = getPath(clusterVersionPath).toOption


  override def masterWrapperEndpoint(): Option[HostAndPort] = cypressHostAndPort(masterWrapperPath).toOption

  private def operation: Option[String] = getPath(operationPath).toOption

  override def operations(): Option[OperationSet] = {
    def isDriverOp: String => Boolean = opId => {
      import CypressDiscoveryService._
      yt.getOperation(new GetOperation(GUID.valueOf(opId)))
        .join()
        .path("full_spec", "tasks", "drivers")
        .isDefined
    }
    operation.map(masterId => {
      val allChildren = if (YtWrapper.exists(childrenOperationsPath)) {
        YtWrapper.listDir(childrenOperationsPath).toSet
      } else {
        Set[String]()
      }
      val children = allChildren.filterNot(isDriverOp)
      val driverOp = allChildren.find(isDriverOp)
      OperationSet(masterId, children, driverOp)
    })
  }

  override def waitAddress(timeout: Duration): Option[Address] = {
    DiscoveryService.waitFor(
      discoverAddress().toOption.filter(a => DiscoveryService.isAlive(a.hostAndPort, 0)),
      timeout,
      s"spark component address in $discoveryPath"
    )
  }

  override def waitAlive(hostPort: HostAndPort, timeout: Duration): Boolean = {
    DiscoveryService.waitFor(
      DiscoveryService.isAlive(hostPort, 0),
      timeout,
      s"address available $hostPort"
    )
  }

  private def removeAddress(transaction: Option[String]): Unit = {
    YtWrapper.listDir(discoveryPath)
      .map(name => s"$discoveryPath/$name")
      .filter(_ != shsPath)
      .foreach(YtWrapper.removeDirIfExists(_, recursive = true, transaction))
  }
}

object CypressDiscoveryService {
  def eventLogPath(discoveryBasePath: String): String = {
    s"$discoveryBasePath/logs/event_log_table"
  }

  implicit def convertOptional[T](opt: Optional[T]): Option[T] = {
    if (opt.isPresent)
      Some(opt.get())
    else
      None
  }

  implicit class YTreeNodeExt(n: YTreeNode) {
    def path(path: String*): Option[YTreeNode] =
      path.foldLeft(Option(n)) { case (no, p) =>
        no.flatMap(v => Option(v.asMap().get(p)))
      }

    def longAttribute(path: String*): Option[Long] = n.path(path:_*).map(_.longValue())
  }
}

case class InvalidCatalogException(message: String) extends RuntimeException(message)
case class EmptyDirectoryException(message: String) extends RuntimeException(message)

