import java.util.concurrent.TimeUnit

import cats.effect.IO
import com.codahale.metrics.graphite.{Graphite, GraphiteReporter}
import com.codahale.metrics.{ConsoleReporter, MetricRegistry}
import org.http4s.server.blaze.BlazeBuilder
import scopt.OptionParser
import cats._
import cats.implicits._
import cats.data._
import cats.syntax._
import cats.effect._
import fs2._

object Main extends StreamApp[IO] {

  case class Config(host: String = "localhost",
                    port: Int = 8090,
                    consoleMetrics: Boolean = false,
                    graphite: Option[String] = None,
                    graphitePrefix: String = "moe.pizza.timerboard")

  val parser = new OptionParser[Config]("timerboard") {
    head("timerboard backend")

    opt[String]("host")
      .action { (x, c) =>
        c.copy(host = x)
      }
      .optional()
      .text("defaults to localhost")
    opt[Int]("port")
      .action { (x, c) =>
        c.copy(port = x)
      }
      .optional()
      .text("defaults to 8090")
    opt[Boolean]("console_metrics")
      .action { (x, c) =>
        c.copy(consoleMetrics = x)
      }
      .optional()
      .text("dump metrics to the console, defaults off")
    opt[String]("graphite")
      .action { (x, c) =>
        c.copy(graphite = Some(x))
      }
      .optional()
      .text("address to the graphite server, sends metrics if enabled")
    opt[String]("graphite_prefix")
      .action { (x, c) =>
        c.copy(graphitePrefix = x)
      }
      .optional()
      .text("prefix for graphite metrics, defaults to moe.pizza.timerboard")

  }

  override def stream(args: List[String], requestShutdown: IO[Unit]): fs2.Stream[IO, StreamApp.ExitCode] = {
    implicit val ec = scala.concurrent.ExecutionContext.global
    parser.parse(args, Config()) match {
      case Some(config) =>
        println("starting")
        val metrics = new MetricRegistry
        if (config.consoleMetrics) {
          ConsoleReporter
            .forRegistry(metrics)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build()
            .start(30, TimeUnit.SECONDS)
        }
        config.graphite.foreach { g =>
          GraphiteReporter
            .forRegistry(metrics)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .prefixedWith(config.graphitePrefix)
            .build(new Graphite(g, 2003))
            .start(1, TimeUnit.SECONDS)

        }
        for {
          sched <- Scheduler.apply[IO](8)
          svc = new StreamingService(metrics, sched, scala.concurrent.ExecutionContext.global)
          _ = println("streaming set up, mounting as http server")
          exit <- BlazeBuilder[IO]
            .mountService(svc.service, "/")
            .bindHttp(config.port, config.host)
            .serve
        } yield exit
      case None =>
        Stream.emit(StreamApp.ExitCode.Error)
    }
  }
}
