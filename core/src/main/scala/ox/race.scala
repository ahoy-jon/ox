package ox

import java.util.concurrent.ArrayBlockingQueue
import scala.annotation.tailrec
import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

def timeout[T](duration: FiniteDuration)(t: => T): T =
  raceSuccess(Right(t))({ Thread.sleep(duration.toMillis); Left(()) }) match
    case Left(_)  => throw new TimeoutException(s"Timed out after $duration")
    case Right(v) => v

def raceSuccess[T](fs: Seq[() => T]): T =
  scoped {
    val result = new ArrayBlockingQueue[Try[T]](fs.size)
    fs.foreach(f => forkHold(result.put(Try(f()))))

    @tailrec
    def takeUntilSuccess(firstException: Option[Throwable], left: Int): T =
      if left == 0 then throw firstException.getOrElse(new NoSuchElementException)
      else
        result.take() match
          case Success(v) => v
          case Failure(e) => takeUntilSuccess(firstException.orElse(Some(e)), left - 1)

    takeUntilSuccess(None, fs.size)
  }

def raceResult[T](fs: Seq[() => T]): T = raceSuccess(fs.map(f => () => Try(f()))).get // TODO optimize

/** Returns the result of the first computation to complete successfully, or if all fail - throws the first exception. */
def raceSuccess[T](f1: => T)(f2: => T): T = raceSuccess(List(() => f1, () => f2))

/** Returns the result of the first computation to complete (either successfully or with an exception). */
def raceResult[T](f1: => T)(f2: => T): T = raceResult(List(() => f1, () => f2))
