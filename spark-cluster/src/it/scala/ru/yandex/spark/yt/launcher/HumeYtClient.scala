package ru.yandex.spark.yt.launcher

import org.scalatest.TestSuite
import ru.yandex.spark.yt.test.LocalYtClient
import ru.yandex.spark.yt.wrapper.client.{ByopConfiguration, ByopRemoteConfiguration, DefaultRpcCredentials, EmptyWorkersListStrategy, YtClientConfiguration}

import scala.concurrent.duration._
import scala.language.postfixOps

trait HumeYtClient extends LocalYtClient {
  self: TestSuite =>

  override protected def conf: YtClientConfiguration = YtClientConfiguration(
    proxy = "hume",
    user = DefaultRpcCredentials.user,
    token = DefaultRpcCredentials.token,
    timeout = 5 minutes,
    proxyRole = None,
    byop = ByopConfiguration.DISABLED,
    masterWrapperUrl = None
  )
}
