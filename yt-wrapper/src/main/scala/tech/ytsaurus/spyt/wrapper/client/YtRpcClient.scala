package tech.ytsaurus.spyt.wrapper.client

import org.slf4j.LoggerFactory
import tech.ytsaurus.client.CompoundClient
import tech.ytsaurus.client.bus.DefaultBusConnector

case class YtRpcClient(id: String, yt: CompoundClient, connector: DefaultBusConnector) extends AutoCloseable {
  private val log = LoggerFactory.getLogger(getClass)

  def close(): Unit = {
    log.info(s"Close yt client $id")
    yt.close()
    connector.close()
    log.info(s"Successfully closed yt client $id")
  }
}
