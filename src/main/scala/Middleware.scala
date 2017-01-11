import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

import org.http4s._
import org.http4s.client.blaze.PooledHttp1Client
import org.http4s.server.HttpMiddleware
import org.http4s.util.CaseInsensitiveString

import scala.concurrent.duration._
import scala.util.Try

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
        nowInstant     <- Try(httpDateFormat.parse(now.value)).toOption.map(Instant.from)
        expiresInstant <- Try(httpDateFormat.parse(expires.value)).toOption.map(Instant.from)
      } yield ChronoUnit.SECONDS.between(nowInstant, expiresInstant).seconds
    }.filter(_.length > 0).getOrElse(Duration.Zero)
  }

  val cacheMiddleware = new HttpMiddleware {
    override def apply(
        v1: Service[Request, Response]): Service[Request, Response] = {
      v1.map { r =>
        (r, getCacheDuration(r))
      }.concurrentlyCache
    }
  }

}
