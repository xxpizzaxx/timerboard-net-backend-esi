import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime, OffsetDateTime, OffsetTime}
import java.util.concurrent.atomic.AtomicInteger

import org.http4s.{Header, Headers, Response}
import org.scalatest.{FlatSpec, MustMatchers}

import scala.concurrent.duration._
import scalaz.concurrent.Task

class MiddlewareSpec extends FlatSpec with MustMatchers {

  "parsing the cache length out of a response" should "work with a normal response" in {
    val headers = List(
      Header("date", "Wed, 04 Jan 2017 00:03:44 GMT"),
      Header("expires", "Wed, 04 Jan 2017 00:03:46 GMT")
    )
    val response = new Response(headers = Headers(headers))
    Middleware.getCacheDuration(response) must equal(2 seconds)
  }

  "parsing the cache length out of a response" should "work with a normal response with a long expiry" in {
    val headers = List(
      Header("date", "Wed, 04 Jan 2017 00:03:44 GMT"),
      Header("expires", "Fri, 06 Jan 2017 23:07:50 GMT")
    )
    val response = new Response(headers = Headers(headers))
    Middleware.getCacheDuration(response) must equal(255846 seconds)
  }

  "parsing the cache length out of a response" should "fail gracefully if there's no current date" in {
    val headers = List(
      Header("expires", "Wed, 04 Jan 2017 00:03:46 GMT")
    )
    val response = new Response(headers = Headers(headers))
    Middleware.getCacheDuration(response) must equal(0 seconds)
  }

  "parsing the cache length out of a response" should "fail gracefully if there's no expiry" in {
    val headers = List(
      Header("date", "Wed, 04 Jan 2017 00:03:44 GMT")
    )
    val response = new Response(headers = Headers(headers))
    Middleware.getCacheDuration(response) must equal(0 seconds)
  }

  "parsing the cache length out of a response" should "fail gracefully if date's malformed" in {
    val headers = List(
      Header("date", "AAAAAAAAAAAAAAA THIS ISN'T A DATE WHAT 44"),
      Header("expires", "Wed, 04 Jan 2017 00:03:46 GMT")
    )
    val response = new Response(headers = Headers(headers))
    Middleware.getCacheDuration(response) must equal(0 seconds)
  }

  "parsing the cache length out of a response" should "fail gracefully if expires is malformed" in {
    val headers = List(
      Header("date", "Wed, 04 Jan 2017 00:03:46 GMT"),
      Header("expires", "AAAAAAAAAAAAAAA THIS ISN'T A DATE WHAT 44")
    )
    val response = new Response(headers = Headers(headers))
    Middleware.getCacheDuration(response) must equal(0 seconds)
  }

  "parsing the cache length out of a response" should "expire immediately if the expiry is in the past" in {
    val headers = List(
      Header("date", "Wed, 04 Jan 2017 00:03:46 GMT"),
      Header("expires", "Wed, 04 Jan 2017 00:03:44 GMT")
    )
    val response = new Response(headers = Headers(headers))
    Middleware.getCacheDuration(response) must equal(0 seconds)
  }

  "Using the cache" should "cause the endpoint to only be hit when it's expired" in {
    import org.http4s._
    import org.http4s.dsl._
    val hits = new AtomicInteger(0)
    val service = HttpService {
      case _ =>
        hits.incrementAndGet()
        Ok("body").map { r =>
          val now = OffsetDateTime.now()
          val in5seconds = now.plusSeconds(5)
          val headers = Headers(
            List(
              Header("date", DateTimeFormatter.RFC_1123_DATE_TIME.format(now)),
              Header("expires",
                     DateTimeFormatter.RFC_1123_DATE_TIME.format(in5seconds))
            ))
          r.copy(headers = headers)
        }
    }
    val cachedService =
      Middleware.cacheMiddleware(methods = List(Method.GET))(service)
    val req = new Request(method = Method.GET, uri = Uri.uri("/path"))
    val r1 = cachedService.run(req).run.headers
    hits.get() must equal(1)
    cachedService.run(req).run.headers must equal(r1)
    cachedService.run(req).run.headers must equal(r1)
    cachedService.run(req).run.headers must equal(r1)
    cachedService.run(req).run.headers must equal(r1)
    hits.get() must equal(1)
    Thread.sleep(5000)
    val r2 = cachedService.run(req).run.headers
    hits.get() must equal(2)
    r2 must not equal (r1)
    cachedService.run(req).run.headers must equal(r2)
    hits.get() must equal(2)
  }

}
