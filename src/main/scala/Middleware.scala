import java.time.{Instant, ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter
import java.time.temporal.{ChronoField, ChronoUnit, TemporalField}

import org.http4s._
import org.http4s.server.HttpMiddleware
import org.http4s.util.CaseInsensitiveString

import scala.concurrent.duration._
import scala.util.Try
import scalaz.Kleisli
import scalaz.stream.Process

object Middleware {
  import KleisliMemo._
  import KleisliCache._

  val memoMiddleware = new HttpMiddleware {
    override def apply(
        v1: Service[Request, Response]): Service[Request, Response] =
      v1.concurrentlyMemoize
  }

  val httpDateFormat =
    DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

  def getCacheDuration(r: Response): FiniteDuration = {
    {
      for {
        now            <- r.headers.get(CaseInsensitiveString("date"))
        expires        <- r.headers.get(CaseInsensitiveString("expires"))
        nowInstant     <- Try(ZonedDateTime.parse(now.value, httpDateFormat)).toOption.map(_.toInstant)
        expiresInstant <- Try(ZonedDateTime.parse(expires.value, httpDateFormat)).toOption.map(_.toInstant)
      } yield ChronoUnit.SECONDS.between(nowInstant, expiresInstant).seconds
    }.filter(_.length > 0).getOrElse(Duration.Zero)
  }

  def cacheMiddleware(methods: List[Method] = Method.registered.toList) = new HttpMiddleware {
    override def apply(
        v1: Service[Request, Response]): Service[Request, Response] = {
      val cached = v1.map { r =>
        val body = r.body.runLog.unsafePerformSync
        ((r, body), getCacheDuration(r))
      }.concurrentlyCache
      HttpService {
        case r if methods.contains(r.method) =>
          cached(r).map{ case (resp, body) =>
            resp.copy(body = Process.emitAll(body))
          }
        case r =>
          v1(r)
      }
    }
  }

}
