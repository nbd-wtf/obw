package wtf.nbd.obw

import scala.concurrent.duration._
import scala.concurrent.Future
import immortan.LNParams.ec

class DebouncedFunctionCanceled extends Exception("debounced function canceled")

package object utils {
  def debounce[A](
      fn: Function[A, Unit],
      duration: FiniteDuration
  ): Function[A, Unit] = {
    val timer = new java.util.Timer()
    var task = new java.util.TimerTask { def run(): Unit = {} }

    def debounced(arg: A): Unit = {
      // every time the debounced function is called

      // clear the timeout that might have existed from before
      task.cancel()

      // create a new timer
      task = new java.util.TimerTask {
        // actually run the function when the timer ends
        def run(): Unit = {
          fn(arg)
        }
      }
      timer.schedule(task, duration.toMillis)
    }

    debounced
  }

  def throttle[A](
      fn: Function[A, Unit],
      duration: FiniteDuration
  ): Function[A, Unit] = {
    var throttling = false
    val timer = new java.util.Timer()
    val task = new java.util.TimerTask {
      def run(): Unit = { throttling = false }
    }

    def throttled(arg: A): Unit = {
      if (!throttling) {
        throttling = true
        timer.schedule(task, duration.toMillis)
        fn(arg)
      }
    }

    throttled
  }

  def firstLast[A](fn: Function[A, Unit]): Function[A, Unit] = {
    var currently: Future[Unit] = Future.successful(())
    var next: Option[A] = None

    def call(arg: A): Unit =
      if (currently.isCompleted) {
        currently = Future { fn(arg) }
          .map { _ =>
            next.foreach { arg =>
              next = None
              currently = Future { fn(arg) }
            }
          }
      } else {
        next = Some(arg)
      }

    call
  }
}
