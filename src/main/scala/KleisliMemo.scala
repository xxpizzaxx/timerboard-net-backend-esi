import net.jodah.expiringmap.{ExpirationPolicy, ExpiringMap}

import scala.collection.concurrent.TrieMap
import cats.effect._
import cats._, cats.implicits._, cats.syntax._, cats.data._

object KleisliMemo {

  sealed abstract class KleisliMemo[M[_], K, V] {
    def apply(z: Kleisli[M, K, V]): Kleisli[M, K, V]
  }

  def kmemo[M[_], K, V](f: Kleisli[M, K, V] => Kleisli[M, K, V]): KleisliMemo[M, K, V] =
    new KleisliMemo[M, K, V] {
      override def apply(z: Kleisli[M, K, V]): Kleisli[M, K, V] = f(z)
    }

  // normal concurrent memoization

  def concurrentKleisliMemo[M[_], K, V](implicit mm: Monad[M]): KleisliMemo[M, K, V] = {
    concurrentKleisliMemo[M, K, V]()
  }

  def concurrentKleisliMemo[M[_], K, V](m: TrieMap[K, V] = new TrieMap[K, V]())(
      implicit mm: Monad[M]): KleisliMemo[M, K, V] = {
    kmemo[M, K, V](f =>
      Kleisli { (k: K) =>
        m.get(k).map(a => mm.point(a)).getOrElse {
          val saver = Kleisli { (v: V) =>
            m.put(k, v)
            mm.point(v)
          }
          (f andThen saver).run(k)
        }
    })
  }

  implicit class MemoizableKleiski[M[_], K, V](k: Kleisli[M, K, V]) {
    def concurrentlyMemoize(implicit mm: Monad[M]) =
      concurrentKleisliMemo(mm).apply(k)
  }

}

object KleisliCache {
  // and with TTLs

  import scala.concurrent.duration.FiniteDuration

  type ResultWithExpiry[T] = (T, FiniteDuration)

  sealed abstract class KleisliCache[M[_], K, V] {
    def apply(z: Kleisli[M, K, ResultWithExpiry[V]]): Kleisli[M, K, V]
  }

  def kcache[M[_], K, V](f: Kleisli[M, K, ResultWithExpiry[V]] => Kleisli[M, K, V]): KleisliCache[M, K, V] =
    new KleisliCache[M, K, V] {
      override def apply(z: Kleisli[M, K, ResultWithExpiry[V]]): Kleisli[M, K, V] = f(z)
    }

  def concurrentKleisliCache[M[_], K, V]()(implicit mm: Monad[M]): KleisliCache[M, K, V] = {
    val m = Cache.create[K, V]()
    kcache[M, K, V](f =>
      Kleisli { (k: K) =>
        m.get(k)
          .map(a => mm.point(a))
          .getOrElse {
            val saver = Kleisli { (r: ResultWithExpiry[V]) =>
              val (v, d) = r
              if (m.get(k).isEmpty) { // only put it in the cache if we've not got one in there
                m.put(k, v, d)
              }
              mm.point(v)
            }
            (f andThen saver).run(k)
          }
    })
  }

  implicit class CacheableKleiski[M[_], K, V](k: Kleisli[M, K, ResultWithExpiry[V]]) {
    def concurrentlyCacheWithExpiringMap(implicit mm: Monad[M]) =
      concurrentKleisliCache[M, K, V]().apply(k)
  }

}
