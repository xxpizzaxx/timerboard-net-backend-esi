import java.util.concurrent.{Executors, ScheduledExecutorService}

import org.http4s.rho.RhoService
import org.http4s._
import eveapi.esi.client._
import EsiClient._
import _root_.argonaut._
import Argonaut._
import ArgonautShapeless._
import argonautCodecs.ArgonautCodecs._
import com.codahale.metrics.MetricRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import com.flipkart.zjsonpatch.JsonDiff
import org.http4s.circe._
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._

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
import scala.util.Try

class StreamingService(metrics: MetricRegistry) {
  import org.http4s.dsl._
  import org.http4s.websocket.WebsocketBits._
  import org.http4s.server.websocket.WS

  val log = LoggerFactory.getLogger(getClass)

  val metric_sov = metrics.meter("urlfetch-sov")
  val metric_sys = metrics.meter("urlfetch-system")
  val metric_ping = metrics.meter("ping")
  val metric_initial = metrics.meter("initial")
  val metric_diff = metrics.meter("diff")

  val esi = new MetricsEsiClient("", metrics, "timerboard-net-backend-esi")

  // TODO replace all the disjunction flattening with validations

  val getSystemName = scalaz.Memo.mutableHashMapMemo { (id: Int) =>
    metric_sys.mark()
    EsiClient.universe.getUniverseSystemsSystemId(id)
      .run(esi)
      .attemptRun
      .toOption
      .map(_.toOption)
      .flatten
      .flatMap(_.solar_system_name)
  }

  case class AllianceInfo(id: Int, ticker: String, name: String)

  val getAllianceName = scalaz.Memo.mutableHashMapMemo { (id: Int) =>
    metric_sys.mark()
    EsiClient.alliance.getAlliancesAllianceId(id)
      .run(esi)
      .attemptRun
      .toOption
      .map(_.toOption)
      .flatten
      .map(x => AllianceInfo(id, x.ticker, x.alliance_name))
  }

  case class SystemNameAndSovCampaign(
      solar_system_name: Option[String],
      event: eveapi.esi.model.Get_sovereignty_campaigns_200_ok,
      alliance: Option[AllianceInfo])

  val topic: Topic[List[SystemNameAndSovCampaign]] =
    scalaz.stream.async.topic[List[SystemNameAndSovCampaign]]({
      time
        .awakeEvery(5 seconds)(Strategy.DefaultStrategy, DefaultScheduler)
        .map {
          _ =>
            metric_sov.mark()
            EsiClient.sovereignty
              .getSovereigntyCampaigns()
              .run(esi)
              .map {
                _.map {
                  res =>
                    val systems = res.map(_.solar_system_id).toSet
                    val alliances = res.flatMap(_.defender_id)
                    val allianceLookups = alliances.map{ id =>
                      Task { getAllianceName(id.toInt) }
                    }
                    val systemLookups = systems.map { id =>
                      Task { (id, getSystemName(id.toInt)) }
                    }.toList
                    val systemResults =
                      Task.gatherUnordered(systemLookups).run.toMap
                    val allianceResults =
                      Task.gatherUnordered(allianceLookups).attemptRun.toOption.toList.flatten.flatten
                    res.map { event =>
                      SystemNameAndSovCampaign(
                        systemResults.get(event.solar_system_id).flatten,
                        event,
                        allianceResults.find(a => event.defender_id.contains(a.id.toInt))
                      )
                    }
                }
              }
              .attemptRun
        }
        .map(_.toOption.map(_.toOption).flatten)
        .flatMap {
          case Some(x) => Process.emit(x)
          case None => Process.empty
        }
    })

  @volatile var lastResponse: List[SystemNameAndSovCampaign] = null

  topic.subscribe
    .map(x => lastResponse = x)
    .run
    .runAsync(f =>
      f.leftMap(t => log.error("failed to update the lastResponse cache", t))
        .rightMap(_ =>
          log.error("lastResponse cache updater exited with Unit")))

  val encoder = EncodeJson.of[List[SystemNameAndSovCampaign]]

  val OM = new ObjectMapper()

  val service = HttpService {
    case r @ GET -> Root / "stream" =>
      val pings = time
        .awakeEvery(10 seconds)(Strategy.DefaultStrategy, DefaultScheduler)
        .map { _ =>
          metric_ping.mark()
          Ping()
        }
      val initial = Process.emitAll(Option(lastResponse).toList)
      val src = wye(initial, topic.subscribe)(wye.mergeHaltR).zipWithPrevious.filter {
        case (x, y) => !x.contains(y) //dedupe
      }.flatMap { r =>
        val mainResponse = r match {
          // transform to JSON
          case (None, current) =>
            metric_initial.mark()
            Some(s"""{"initial":${encoder(current).toString}}""") // first run!
          case (Some(prev), current) => // we've had a change in the JSON
            metric_diff.mark()
            val pjson = OM.readTree(encoder(prev).toString)
            val cjson = OM.readTree(encoder(current).toString)
            val diffs = JsonDiff.asJson(pjson, cjson).toString
            Some(s"""{"diff":${diffs}}""")
          case _ =>
            log.error("unknown result in stream")
            None
        }
        Process.emitAll(List(mainResponse).flatten)
      }.map { body =>
        Text(body)
      }
      WS(Exchange(wye(pings, src)(wye.mergeHaltR), Process.halt))
  }

}
