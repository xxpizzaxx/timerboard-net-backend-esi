import org.http4s.rho.RhoService
import org.http4s._
import eveapi.esi.client._
import eveapi.esi.api._
import EsiClient._
import _root_.argonaut._
import Argonaut._
import ArgonautShapeless._
import eveapi.esi.api.ArgonautCodecs._
import com.codahale.metrics.MetricRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import com.flipkart.zjsonpatch.JsonDiff
import org.http4s.circe._
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.client.blaze.PooledHttp1Client

import scalaz._
import Scalaz._
import scalaz.concurrent.{Strategy, Task}
import spray.caching.{Cache, LruCache}

import scalaz.stream.{Exchange, Process, time}
import scalaz.stream.async.topic
import scala.concurrent.duration._
import scalaz.stream.async.mutable.Topic
import scala.concurrent.ExecutionContext.Implicits.global
import scalaz.stream.{DefaultScheduler, Exchange, Process, wye}
import org.http4s.util.threads.threadFactory
import org.slf4j.LoggerFactory
import scalaz.concurrent.Task

/**
  * Created by andi on 10/01/17.
  */
object ComposableClient {

  def main(args: Array[String]): Unit = {
    val client = PooledHttp1Client.apply()
    val memoclient = Middleware.memoMiddleware(client.toService(x => Task.delay(x)))
    val esi = new EsiClient("my useragent goes here", memoclient)

    val res = EsiClient.alliance.getAlliancesAllianceId(1354830081).run(esi).unsafePerformSync
    println(res)

    client.shutdown.unsafePerformSync
  }

}
