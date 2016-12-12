import scala.collection.concurrent.TrieMap
import scalaz.concurrent.Task
import scalaz._, Scalaz._

object KleisliMemo {

  sealed abstract class KleisliMemo[M[_], K, V] {
    def apply(z: Kleisli[M, K, V]): Kleisli[M, K, V]
  }

  def kmemo[M[_], K, V](f: Kleisli[M, K, V] => Kleisli[M, K, V]): KleisliMemo[M, K, V] = new KleisliMemo[M, K, V] {
    override def apply(z: Kleisli[M, K, V]): Kleisli[M, K, V] = f(z)
  }

  def concurrentKleisliMemo[M[_], K, V](implicit mm: Monad[M]): KleisliMemo[M, K, V] = {
    concurrentKleisliMemo[M, K, V]()
  }

  def concurrentKleisliMemo[M[_], K, V](
      m: TrieMap[K, V] = new TrieMap[K, V]())(implicit mm: Monad[M]): KleisliMemo[M, K, V] = {
    kmemo[M, K, V](f =>
      Kleisli { (k: K) =>
        m.get(k).map(a => mm.point(a)).getOrElse {
          val saver = Kleisli{ (v: V) =>
            m.put(k, v)
            mm.point(v)
          }
          (f >=> saver).run(k)
        }
      }
    )
  }

  implicit class MemoizableKleiski[M[_], K, V](k: Kleisli[M, K, V]) {
    def concurrentlyMemoize(implicit mm: Monad[M]) = concurrentKleisliMemo(mm).apply(k)
  }

}
