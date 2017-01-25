import eveapi.esi.client._
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

/**
  * Created by andi on 24/01/17.
  */
object ClientCacheTest extends App {

  val client = PooledHttp1Client.apply()

  val esi = new EsiClient("test-client", Middleware.cacheMiddleware()(client.toHttpService))

  val req = EsiClient.alliance.getAlliances()

  println(req)

  val r = req.run(esi)


  while(true) {
    println(r.unsafePerformSync)
    readLine()
  }




}
