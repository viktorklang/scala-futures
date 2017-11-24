/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.future.impl
import scala.concurrent.{ExecutionContext, CanAwait, TimeoutException, ExecutionException }
import scala.future.{ Future, OnCompleteRunnable }
import scala.future.Future.InternalCallbackExecutor
import scala.concurrent.duration.Duration
import scala.annotation.{ tailrec, unchecked }
import scala.util.control.{ NonFatal, ControlThrowable }
import scala.util.{ Try, Success, Failure }
import scala.runtime.NonLocalReturnControl
import scala.language.higherKinds
import java.util.concurrent.locks.AbstractQueuedSynchronizer
import java.util.concurrent.atomic.AtomicReference
import java.util.Objects.requireNonNull

private[future] final object Promise {
   /**
    * Latch used to implement waiting on a DefaultPromise's result.
    *
    * Inspired by: http://gee.cs.oswego.edu/cgi-bin/viewcvs.cgi/jsr166/src/main/java/util/concurrent/locks/AbstractQueuedSynchronizer.java
    * Written by Doug Lea with assistance from members of JCP JSR-166
    * Expert Group and released to the public domain, as explained at
    * http://creativecommons.org/publicdomain/zero/1.0/
    */
    private final class CompletionLatch[T] extends AbstractQueuedSynchronizer with (Try[T] => Unit) {
      //@volatie not needed since we use acquire/release
      /*@volatile*/ private[this] var _result: Try[T] = null
      final def result: Try[T] = _result
      override protected def tryAcquireShared(ignored: Int): Int = if (getState != 0) 1 else -1
      override protected def tryReleaseShared(ignore: Int): Boolean = {
        setState(1)
        true
      }
      override def apply(value: Try[T]): Unit = {
        _result = value // This line MUST go before releaseShared
        releaseShared(1)
      }
    }

    private final class Link[T](to: DefaultPromise[T]) extends AtomicReference[DefaultPromise[T]](to) {
      final def promise(): DefaultPromise[T] = compressedRoot(this)

      @tailrec private final def root(): DefaultPromise[T] = {
        val cur = this.get()
        val target = cur.get()
        if (target.isInstanceOf[Link[T]]) target.asInstanceOf[Link[T]].root()
        else cur
      }

      @tailrec private[this] final def compressedRoot(linked: Link[T]): DefaultPromise[T] = {
        val current = linked.get()
        val target = linked.root()
        if ((current eq target) || compareAndSet(current, target)) target
        else compressedRoot(this)
      }

      final def link(target: Link[T]): DefaultPromise[T] = {
          val current = get()
          val newTarget = target.promise()
          if ((current eq newTarget) || compareAndSet(current, newTarget)) newTarget
          else link(target)
      }

      final def unlink(owner: DefaultPromise[T]): Boolean = {
        val r = root().get()
        if (r.isInstanceOf[Try[T]]) unlink(owner, r.asInstanceOf[Try[T]]) else false
      }

      @tailrec private final def unlink(owner: DefaultPromise[T], value: Try[T]): Boolean = {
        val l = owner.get()
        if (l.isInstanceOf[Link[T]]) {
          if (owner.compareAndSet(l, value)) unlink(l.asInstanceOf[Link[T]].get(), value)
          else unlink(owner, value)
        } else true
      }
    }

    // requireNonNull is paramount to guard against null completions
    private[this] final def resolve[T](value: Try[T]): Try[T] =
      if (requireNonNull(value).isInstanceOf[Success[T]]) value
      else {
        val t = value.asInstanceOf[Failure[T]].exception
        if (t.isInstanceOf[ControlThrowable] || t.isInstanceOf[InterruptedException] || t.isInstanceOf[Error]) {
          if (t.isInstanceOf[NonLocalReturnControl[T @unchecked]])
            Success(t.asInstanceOf[NonLocalReturnControl[T]].value)
          else
            Failure(new ExecutionException("Boxed Exception", t))
        } else value
      }

  // Left non-final to enable addition of extra fields by Java/Scala converters in scala-java8-compat.
  class DefaultPromise[T] private[this] (initial: AnyRef) extends AtomicReference[AnyRef](initial) with scala.future.Promise[T] with scala.future.Future[T] {
    /**
     * Constructs a new, completed, Promise.
     */
    def this(result: Try[T]) = this(resolve(result): AnyRef)

    /**
     * Constructs a new, un-completed, Promise.
     */
    def this() = this(null: AnyRef)

    /**
     * Returns the associaed `Future` with this `Promise`
     */
    override def future: Future[T] = this

    override def transform[S](f: Try[T] => Try[S])(implicit executor: ExecutionContext): Future[S] =
      dispatchOrAddCallbacks(new XformPromise[T, Try, S](f, executor))

    override def transformWith[S](f: Try[T] => Future[S])(implicit executor: ExecutionContext): Future[S] =
      dispatchOrAddCallbacks(new XformPromise[T, Future, S](f, executor))

    override def onFailure[U](@deprecatedName('callback) pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Unit =
      if (!value0.isInstanceOf[Success[T]]) super[Future].onFailure(pf) // Short-circuit if we get a Failure, consider using get() iso value0

    override def onSuccess[U](pf: PartialFunction[T, U])(implicit executor: ExecutionContext): Unit = 
      if (!value0.isInstanceOf[Failure[T]]) super[Future].onSuccess(pf)  // Short-circuit if we get a Success, consider using get() iso value0

    override def foreach[U](f: T => U)(implicit executor: ExecutionContext): Unit =
      if (!value0.isInstanceOf[Failure[T]]) super[Future].foreach(f)  // Short-circuit if we get a Success, consider using get() iso value0

    override def flatMap[S](f: T => Future[S])(implicit executor: ExecutionContext): Future[S] = 
      if (!value0.isInstanceOf[Failure[T]]) super[Future].flatMap(f) // Short-circuit if we get a Success, consider using get() iso value0
      else this.asInstanceOf[Future[S]]

    override def map[S](f: T => S)(implicit executor: ExecutionContext): Future[S] =
      if (!value0.isInstanceOf[Failure[T]]) super[Future].map(f) // Short-circuit if we get a Success, consider using get() iso value0
      else this.asInstanceOf[Future[S]]

    override def filter(@deprecatedName('pred) p: T => Boolean)(implicit executor: ExecutionContext): Future[T] =
      if (!value0.isInstanceOf[Failure[T]]) super[Future].filter(p) // Short-circuit if we get a Success, consider using get() iso value0
      else this

    override def collect[S](pf: PartialFunction[T, S])(implicit executor: ExecutionContext): Future[S] =
      if (!value0.isInstanceOf[Failure[T]]) super[Future].collect(pf) // Short-circuit if we get a Success, consider using get() iso value0
      else this.asInstanceOf[Future[S]]

    override def recoverWith[U >: T](pf: PartialFunction[Throwable, Future[U]])(implicit executor: ExecutionContext): Future[U] =
      if (!value0.isInstanceOf[Success[T]]) super[Future].recoverWith(pf) // Short-circuit if we get a Failure, consider using get() iso value0
      else this.asInstanceOf[Future[U]]

    override def recover[U >: T](pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Future[U] =
      if (!value0.isInstanceOf[Success[T]]) super[Future].recover(pf) // Short-circuit if we get a Failure, consider using get() iso value0
      else this.asInstanceOf[Future[U]]

    override def mapTo[S](implicit tag: scala.reflect.ClassTag[S]): Future[S] =
      if (!value0.isInstanceOf[Failure[T]]) super[Future].mapTo[S](tag) // Short-circuit if we get a Success, consider using get() iso value0
      else this.asInstanceOf[Future[S]]

    override def toString: String = toString0

    @tailrec private final def toString0: String = {
      val state = get()
      if (state.isInstanceOf[Try[T]]) "Future("+state+")"
      else if (state.isInstanceOf[Link[T]]) state.asInstanceOf[Link[T]].promise().toString0
      else /*if ((state eq null) || state.isInstanceOf[Callbacks[T]]) */ "Future(<not completed>)"
    }

    /** Try waiting for this promise to be completed.
     * Returns true if it completed at the end of this waiting time.
     * Does not allow Duration.Undefined as a parameter, and throws IllegalArgumentException
     * if it is passed in.
     */
    protected final def tryAwait(atMost: Duration): Boolean = tryAwait0(atMost) ne null

    private[this] final def tryAwait0(atMost: Duration): Try[T] =
      if (atMost ne Duration.Undefined) {
        val v = value0
        if ((v ne null) || atMost <= Duration.Zero) v
        else {
          val l = new CompletionLatch[T]()
          onComplete(l)(InternalCallbackExecutor)

          if (atMost.isFinite)
            l.tryAcquireSharedNanos(1, atMost.toNanos)
          else
            l.acquireSharedInterruptibly(1)

          l.result
        }
      } else throw new IllegalArgumentException("Cannot wait for Undefined duration of time")

    @throws(classOf[TimeoutException])
    @throws(classOf[InterruptedException])
    final def ready(atMost: Duration)(implicit permit: CanAwait): this.type = {
      val v = tryAwait0(atMost)
      if (v ne null) this
      else throw new TimeoutException("Future timed out after [" + atMost + "]")
    }

    @throws(classOf[Exception])
    final def result(atMost: Duration)(implicit permit: CanAwait): T = {
      val v = tryAwait0(atMost)
      if (v ne null) v.get // returns the value, or throws the contained exception
      else throw new TimeoutException("Future timed out after [" + atMost + "]")
    }

    override final def isCompleted: Boolean = value0 ne null

    override def value: Option[Try[T]] = Option(value0)

    @tailrec
    private final def value0: Try[T] = {
      val state = get()
      if (state.isInstanceOf[Try[T]]) state.asInstanceOf[Try[T]]
      else if (state.isInstanceOf[Link[T]]) state.asInstanceOf[Link[T]].promise().value0
      else /*if ((state eq null) || state.isInstanceOf[Callbacks[T]])*/ null
    }

    override final def tryComplete(value: Try[T]): Boolean = {
      val state = get()
      if (state.isInstanceOf[Try[T]]) false
      else /*if (state.isInstaceOf[Link[T]]*/ {
        val r = tryComplete0(state, resolve(value))
        if (state.isInstanceOf[Link[T]])
          state.asInstanceOf[Link[T]].unlink(this)
        r
      }
    }

    @tailrec
    private final def tryComplete0(state: AnyRef, resolved: Try[T]): Boolean = {
      val Noop = state eq null
      if (Noop || state.isInstanceOf[Callbacks[T]]) {
        if (compareAndSet(state, resolved)) {
          if (Noop) ()
          else submitWithValue(state.asInstanceOf[Callbacks[T]], resolved)
          true
        } else tryComplete0(get(), resolved)
      } else if (state.isInstanceOf[Link[T]]) {
        val p = state.asInstanceOf[Link[T]].promise()
        p.tryComplete0(p.get(), resolved) // Use this to get tailcall optimization and avoid re-resolution
      } else /* if(state.isInstanceOf[Try[T]]) */ false
    }

    override final def onComplete[U](func: Try[T] => U)(implicit executor: ExecutionContext): Unit =
      dispatchOrAddCallbacks(new XformPromise[T, ({type Id[+a] = a})#Id, U](func, executor))

    /** Tries to add the callback, if already completed, it dispatches the callback to be executed.
     *  Used by `onComplete()` to add callbacks to a promise and by `link()` to transfer callbacks
     *  to the root promise when linking two promises together.
     */
    @tailrec private final def dispatchOrAddCallbacks[C <: Callbacks[T]](callbacks: C): C = {
      val state = get()
      if (state.isInstanceOf[Try[T]]) {
        submitWithValue(callbacks, state.asInstanceOf[Try[T]])
        callbacks
      } else if ((state eq null) || state.isInstanceOf[Callbacks[T]]) {
        val newCallbacks = if (state eq null) callbacks else new ManyCallbacks[T](callbacks, state.asInstanceOf[Callbacks[T]])
        if(compareAndSet(state, newCallbacks)) callbacks
        else dispatchOrAddCallbacks(callbacks)
      } else /*if (state.isInstanceOf[Link[T]])*/
        state.asInstanceOf[Link[T]].promise().dispatchOrAddCallbacks(callbacks)
    }

    private[this] final def submitWithValue(cb: Callbacks[T], v: Try[T]): Unit = {
      if (cb.isInstanceOf[ManyCallbacks[T]]) {
        val m = cb.asInstanceOf[ManyCallbacks[T]]
        submitWithValue(m.first, v) // TODO: this will grow the stack—needs real-world proofing
        submitWithValue(m.last, v)
      } else cb.asInstanceOf[XformCallback[T]].submitWithValue(v)
    }

    /** Link this promise to the root of another promise.
     *  Should only be be called by transformWith.
     */
    protected[future] final def linkRootOf(target: DefaultPromise[T]): Unit =
      if (this ne target) {
        val state = get()
        if (state.isInstanceOf[Try[T]]) {
          if (!target.tryComplete(state.asInstanceOf[Try[T]]))
            throw new IllegalStateException("Cannot link completed promises together")
        } else if (state.isInstanceOf[Link[T]]) state.asInstanceOf[Link[T]].link(new Link(target))
        else /*if ((state eq null) || state.isInstanceOf[Callbacks[T]]) */
          tryLink(state, new Link(target))
      }

    /** Link this promise to another promise so that both promises share the same
     *  externally-visible state. Depending on the current state of this promise, this
     *  may involve different things. For example, any onComplete listeners will need
     *  to be transferred.
     *
     *  If this promise is already completed, then the same effect as linking -
     *  sharing the same completed value - is achieved by simply sending this
     *  promise's result to the target promise.
     *
     *  Should only be called by linkRootOf().
     */
    @tailrec
    private[this] final def tryLink(state: AnyRef, target: Link[T]): Unit = {
      val promise = target.promise()
      if (this ne promise) {
        val Noop = state eq null
        if (Noop || state.isInstanceOf[Callbacks[T]]) {
          if (compareAndSet(state, target)) {
            if (!Noop)
              promise.dispatchOrAddCallbacks(state.asInstanceOf[Callbacks[T]])
          } else tryLink(get(), target)
        } else linkRootOf(promise) // If the current state is not Callbacks, fall back to normal linking
      }
    }
  }

  /* Marker trait
   */
  sealed trait Callbacks[-T]

  type XformCallback[-F] = XformPromise[F, Nothing, _]

  final class XformPromise[-F, -TM[+_], T] private[this] (
    private[this] final var _fun: Try[F] => TM[T],
    private[this] final var _arg: AnyRef
  ) extends DefaultPromise[T]() with Callbacks[F] with Runnable with OnCompleteRunnable {
    def this(f: Try[F] => TM[T], ec: ExecutionContext) = this(f, ec.prepare(): AnyRef)

    // Gets invoked when a value is available, schedules it to be run():ed by the ExecutionContext
    // submitWithValue *happens-before* run(), through ExecutionContext.execute.
    // Invariant: _arg is `ExecutionContext`
    // requireNonNull(v) will hold as guarded by `resolve`
    final def submitWithValue(v: Try[F]): Unit = {
      val executor = _arg.asInstanceOf[ExecutionContext]
      try {
        _arg = v
        executor.execute(this) // Safe publication of _arg = v (and _fun)
      }
      catch {
        case t if NonFatal(t) =>
          if (!this.tryFailure(t))
            executor.reportFailure(t)
      }
    }

    // Gets invoked by run()
    // TODO: consider different encoding where result handling is tableswitched iso branched
    private[this] final def complete(r: TM[T]): Unit =
      if (r.isInstanceOf[Try[T]])
        this.complete(r.asInstanceOf[Try[T]])
      else if (r.isInstanceOf[Future[T]]) {
        if (r.isInstanceOf[DefaultPromise[T]])
          r.asInstanceOf[DefaultPromise[T]].linkRootOf(this)
        else
          this.completeWith(r.asInstanceOf[Future[T]])
      } else //if (r.isInstanceOf[T @unchecked])
        this.success(r.asInstanceOf[T])
      // else throw new IllegalStateException("Result value of type ${r.getClass} not valid")

    // Gets invoked by the ExecutionContext, when we have a value to transform.
    // Invariant: if (v.isInstanceOf[Try[F]] && (fun ne null))
    override final def run(): Unit =
        try complete(_fun(_arg.asInstanceOf[Try[F]])) catch {
          case t if NonFatal(t) => this.tryFailure(t)
        } finally { // allow these to GC
          _fun = null
          _arg = null
        }

    override final def toString: String = super[DefaultPromise].toString
  }

  final class ManyCallbacks[-T](final val first: Callbacks[T], final val last: Callbacks[T]) extends Callbacks[T] {
    override final def toString: String = "ManyCallbacks"
  }
}
