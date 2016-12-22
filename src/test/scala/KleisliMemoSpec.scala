import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.{FlatSpec, MustMatchers}

import scala.collection.concurrent.TrieMap
import scalaz.concurrent.Task

class KleisliMemoSpec extends FlatSpec with MustMatchers {

  "memoizing a task" should "cause it's body to only be run once if run multiple times with the same input" in {

    @volatile var counter = 0

    import scalaz._
    val func = KleisliMemo.concurrentKleisliMemo(Monad[Task]) {
      Kleisli { (input: Int) =>
        Task {
          println(s"doubling $input")
          counter += 1
          input * 2
        }
      }
    }

    func(2).run must equal(4)
    counter must equal(1)
    func(2).run must equal(4)
    counter must equal(1)
    func(2).run must equal(4)
    counter must equal(1)
    func(3).run must equal(6)
    counter must equal(2)
    func(2).run must equal(4)
    counter must equal(2)
  }

  "memoizing a task using the helper" should "cause it's body to only be run once if run multiple times with the same input" in {

    @volatile var counter = 0

    import KleisliMemo._

    import scalaz._
    val func = Kleisli[Task, Int, Int] { (input: Int) =>
      Task {
        println(s"doubling $input")
        counter += 1
        input * 2
      }
    }.concurrentlyMemoize

    func(2).run must equal(4)
    counter must equal(1)
    func(2).run must equal(4)
    counter must equal(1)
    func(2).run must equal(4)
    counter must equal(1)
    func(3).run must equal(6)
    counter must equal(2)
    func(2).run must equal(4)
    counter must equal(2)
  }


  "memoizing a task which can fail" should "only save the successful attempts" in {
    import scalaz._

    var counters = new TrieMap[Int, AtomicInteger]()

    val func = Kleisli[Task, Int, Int] { (input: Int) =>
      Task[Int] {
        val c = counters.getOrElseUpdate(input, new AtomicInteger(0))
        val value = c.incrementAndGet()
        assert(value == 2)
        value
      }
    }

    val map = new TrieMap[Int, Int]()
    val memoized = KleisliMemo.concurrentKleisliMemo[Task, Int, Int](map).apply(func)

    memoized(0).attempt.run.isLeft must equal(true)
    memoized(0).attempt.run.toOption.get must equal(2)
    memoized(0).attempt.run.toOption.get must equal(2)
    memoized(0).attempt.run.toOption.get must equal(2)

    val expectedmap = new TrieMap[Int, Int]()
    expectedmap.put(0, 2)
    map must equal(expectedmap)
  }

  "caching a task which can fail" should "cache it during the time period stated" in {
    import scalaz._
    import scala.concurrent.duration._
    import KleisliMemo._

    var counters = new TrieMap[Int, AtomicInteger]()

    val cached = Kleisli[Task, Int, ResultWithExpiry[Int]] { (input: Int) =>
      Task[ResultWithExpiry[Int]] {
        val c = counters.getOrElseUpdate(input, new AtomicInteger(0))
        val value = c.incrementAndGet()
        (value, 2 seconds)
      }
    }.concurrentlyCache

    cached(0).attempt.run.toOption.get must equal(1)
    cached(0).attempt.run.toOption.get must equal(1)
    cached(0).attempt.run.toOption.get must equal(1)

    Thread.sleep(3000)

    cached(0).attempt.run.toOption.get must equal(2)

    counters.get(0).get.get must equal(2)
  }

}
