import java.util.concurrent.{Executors, ScheduledExecutorService}

import org.http4s._
import org.http4s.client.blaze.PooledHttp1Client
import eveapi.esi.client._
import EsiClient._
import eveapi.esi.api.CirceCodecs._
import io.circe.java8.time._
import com.codahale.metrics.MetricRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import com.flipkart.zjsonpatch.JsonDiff
import org.http4s.circe._
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import cats._
import cats.syntax._
import cats.implicits._
import cats.data._
import cats.effect._
import fs2._
import fs2.async.mutable.Topic
import org.http4s.client.DisposableResponse
import org.http4s.server.websocket.WebSocketBuilder

import scala.concurrent.duration._
import org.http4s.util.threads.threadFactory
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.util.Try

class StreamingService(metrics: MetricRegistry, scheduler: Scheduler, ec: ExecutionContext) {
  import org.http4s.dsl.io._
  import org.http4s.websocket.WebsocketBits._
  import org.http4s.server.websocket.WS

  val log = LoggerFactory.getLogger(getClass)
  log.info("starting streaming service")


  val client = PooledHttp1Client.apply[IO](maxWaitQueueLimit = 2048)

  val esi = new EsiClient[IO](useragent = "timerboard-net-esi-crawler", baseUri = Uri.unsafeFromString("https://esi.evetech.net"), client = client.open)

  import KleisliMemo._

  val getSystemName = Kleisli[IO, Int, Option[String]] { (id: Int) =>
    IO {
      EsiClient.universe
        .getUniverseSystemsSystemId(id)
        .run(esi)
        .map {
          _.toOption.map {
            _.name
          }
        }
    }.flatten
  }.concurrentlyMemoize

  case class AllianceInfo(id: Int, ticker: String, name: String)

  val getAllianceName = Kleisli[IO, Int, Option[AllianceInfo]] { (id: Int) =>
    IO {
      EsiClient.alliance
        .getAlliancesAllianceId(id)
        .run(esi)
        .map {
          _.toOption.map(x => AllianceInfo(id, x.ticker, x.name))
        }
    }.flatten
  }.concurrentlyMemoize

  case class SystemNameAndSovCampaign(solar_system_name: Option[String],
                                      event: eveapi.esi.model.Get_sovereignty_campaigns_200_ok,
                                      alliance: Option[AllianceInfo])

  val topicec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))
  val topic: Topic[IO, List[SystemNameAndSovCampaign]] = {
    implicit val e = topicec
    Topic.apply[IO, List[SystemNameAndSovCampaign]](List.empty).unsafeRunSync()
  }

  val crawlerec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))
  val crawler: Stream[IO, List[SystemNameAndSovCampaign]] = {
    implicit val e = crawlerec
    scheduler
      .awakeEvery[IO](5 seconds)
      .flatMap {
        _ =>
          log.info("crawling sov")
          Stream.attemptEval(EsiClient.sovereignty
            .getSovereigntyCampaigns()
            .run(esi)
            .map {
              _.map {
                res =>
                  val systems   = res.map(_.solar_system_id).toSet
                  val alliances = res.flatMap(_.defender_id)
                  val allianceLookups = alliances.map { id =>
                    getAllianceName(id.toInt)
                  }
                  val systemLookups = systems.map { id =>
                    getSystemName(id.toInt).map { x =>
                      (id, x)
                    }
                  }.toList
                  for {
                    systemResults <- systemLookups.map(IO.shift(ec) *> _).parSequence
                    systemLookup = systemResults.toMap
                    allianceResults <- allianceLookups.map(IO.shift(ec) *> _).parSequence
                    allianceLookup = allianceResults.flatten
                  } yield { res.map { event =>
                    SystemNameAndSovCampaign(
                      systemLookup.get(event.solar_system_id).flatten,
                      event,
                      allianceLookup.find(a => event.defender_id.contains(a.id.toInt))
                    )
                  } }
              }
            })
      }
      .map(_.toOption.map(_.toOption).flatten)
      .flatMap {
        case Some(x) => Stream.eval(x).covary[IO]
        case None    => Stream.empty.covary[IO]
      }
  }

  val runcrawler = (IO.shift(ec) *> crawler.to(topic.publish).compile.drain).unsafeRunAsync(_ => ())

  @volatile var lastResponse: List[SystemNameAndSovCampaign] = null

  val updater = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1))

  (IO.shift(updater) *> topic.subscribe(1024)
    .map{x => lastResponse = x; x}
    .compile
    .drain)
    .unsafeRunAsync(_ => ())

  val OM = new ObjectMapper()

  val service = HttpService[IO] {
    case r @ GET -> Root / "stream" =>
      implicit val e = ec
      val pings = scheduler
        .awakeEvery[IO](10 seconds)
        .map { _ =>
          Ping()
        }
      val initial = Stream.emits(Option(lastResponse).toList)
      val src = initial.mergeHaltR(topic.subscribe(1024)).zipWithPrevious
        .filter {
          case (x, y) => !x.contains(y) //dedupe
        }
        .flatMap { r =>
          val mainResponse = r match {
            // transform to JSON
            case (None, current) =>
              Some(s"""{"initial":${current.asJson.noSpaces}}""") // first run!
            case (Some(prev), current) => // we've had a change in the JSON
              val pjson = OM.readTree(prev.asJson.toString)
              val cjson = OM.readTree(current.asJson.toString)
              val diffs = JsonDiff.asJson(pjson, cjson).toString
              Some(s"""{"diff":${diffs}}""")
            case _ =>
              log.error("unknown result in stream")
              None
          }
          Stream.emits(List(mainResponse).flatten)
        }
        .map { body =>
          Text(body)
        }
      WebSocketBuilder[IO].build(pings.mergeHaltR(src), Sink { w: WebSocketFrame => IO {()} })
  }

}
