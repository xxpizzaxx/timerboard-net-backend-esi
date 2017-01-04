import org.http4s.{Header, Headers, Response}
import org.scalatest.{FlatSpec, MustMatchers}

import scala.concurrent.duration._

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

}
