/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.future

import scala.concurrent.{ExecutionContext, CanAwait}
import java.util.ArrayDeque
import java.util.concurrent.Executor
import scala.annotation.tailrec

/**
 * Mixin trait for an Executor
 * which groups multiple nested `Runnable.run()` calls
 * into a single Runnable passed to the original
 * Executor. This can be a useful optimization
 * because it bypasses the original context's task
 * queue and keeps related (nested) code on a single
 * thread which may improve CPU affinity. However,
 * if tasks passed to the Executor are blocking
 * or expensive, this optimization can prevent work-stealing
 * and make performance worse. Also, some ExecutionContext
 * may be fast enough natively that this optimization just
 * adds overhead.
 * The default ExecutionContext.global is already batching
 * or fast enough not to benefit from it; while
 * `fromExecutor` and `fromExecutorService` do NOT add
 * this optimization since they don't know whether the underlying
 * executor will benefit from it.
 * A batching executor can create deadlocks if code does
 * not use `scala.concurrent.blocking` when it should,
 * because tasks created within other tasks will block
 * on the outer task completing.
 * This executor may run tasks in any order, including LIFO order.
 * There are no ordering guarantees.
 *
 * WARNING: The underlying Executor's execute-method must not execute the submitted Runnable
 * in the calling thread synchronously. It must enqueue/handoff the Runnable.
 */
 trait BatchingExecutor extends Executor {
  private final class Batch(size: Int) extends ArrayDeque[Runnable](size) with Runnable with BlockContext with (BlockContext => Unit) {
    private[this] var parentBlockContext: BlockContext = _

    def this(r: Runnable) = {
      this(4)
      addLast(r)
    }

    final def executor: BatchingExecutor = BatchingExecutor.this
    
    // this method runs in the delegate ExecutionContext's thread
    override final def run(): Unit = BlockContext.usingBlockContext(this)(this)

    override final def apply(prevBlockContext: BlockContext): Unit = {
      parentBlockContext = prevBlockContext
      runAll()
      parentBlockContext = null
    }

    @tailrec private[this] final def runAll(): Unit = {
      val next = pollLast()
      if (next ne null) {
        try next.run() catch {
          case t: Throwable =>
            parentBlockContext = null // Need to reset this before re-submitting it
            unbatchedExecute(this) //TODO what if this submission fails?
            throw t
         }
        runAll()
      }
    }

    override def blockOn[T](thunk: => T)(implicit permission: CanAwait): T = {
      val pbc = parentBlockContext
      if(!isEmpty) { // if we know there will be blocking, we don't want to keep tasks queued up because it could deadlock.
        val b = new Batch(math.max(4, this.size))
        b.addAll(this)
        this.clear()
        unbatchedExecute(b)
      }
      pbc match {
        case null => try thunk finally throw new IllegalStateException("BUG in BatchingExecutor.Batch: parentBlockContext is null")
        case some => some.blockOn(thunk) // now delegate the blocking to the previous BC
      }
    }
  }

  protected def unbatchedExecute(r: Runnable): Unit

  override def execute(runnable: Runnable): Unit = {
    if (batchable(runnable)) {
      BlockContext.current match {
        case b: Batch if b.executor eq this => b.addLast(runnable) // If we're currently executing a Batch then add runnable to current batch
        case _ => unbatchedExecute(new Batch(runnable)) // If we aren't in batching mode yet, enqueue batch
      }
    } else unbatchedExecute(runnable) // If not batchable, just delegate to underlying
  }

  /** Override this to define which runnables will be batched. */
  def batchable(runnable: Runnable): Boolean = runnable.isInstanceOf[OnCompleteRunnable]
}
