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
import scala.annotation.{ tailrec, unchecked, switch }
import scala.util.control.{ NonFatal, ControlThrowable }
import scala.util.{ Try, Success, Failure }
import scala.runtime.NonLocalReturnControl
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

      @tailrec private[this] def root(of: DefaultPromise[T]): DefaultPromise[T] = {
        val value = of.get()
        if (value.isInstanceOf[Link[T]]) root(value.asInstanceOf[Link[T]].get())
        else of
      }

      @tailrec private[this] final def compressedRoot(linked: Link[T]): DefaultPromise[T] = {
        val current = linked.get()
        val target = root(current)
        // This tries to make the ideal Links point directly to the *end* of the link chain. Can we do better when the end is resolved?
        if ((current eq target) || compareAndSet(current, target)) target
        else compressedRoot(linked)
      }

      final def link(target: DefaultPromise[T]): DefaultPromise[T] = {
          val current = get()
          val newTarget = root(target)
          if ((current eq newTarget) || compareAndSet(current, newTarget)) newTarget
          else link(target)
      }

      final def unlink(owner: DefaultPromise[T]): Boolean = {
        val r = root(get()).get()
        if (r.isInstanceOf[Try[T]]) unlink(owner, r.asInstanceOf[Try[T]]) else false
      }

      @tailrec private[this] final def unlink(owner: DefaultPromise[T], value: Try[T]): Boolean = {
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
    def this() = this(Noop: AnyRef)

    /**
     * Returns the associaed `Future` with this `Promise`
     */
    override def future: Future[T] = this

    override def transform[S](f: Try[T] => Try[S])(implicit executor: ExecutionContext): Future[S] =
      dispatchOrAddCallbacks(new XformPromise[T, S](Xform_transform, f, executor))

    override def transformWith[S](f: Try[T] => Future[S])(implicit executor: ExecutionContext): Future[S] =
      dispatchOrAddCallbacks(new XformPromise[T, S](Xform_transformWith, f, executor))

    override def onFailure[U](@deprecatedName('callback) pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Unit =
      if (!get.isInstanceOf[Success[T]]) super[Future].onFailure(pf) // Short-circuit if we get a Failure

    override def onSuccess[U](pf: PartialFunction[T, U])(implicit executor: ExecutionContext): Unit = 
      if (!get.isInstanceOf[Failure[T]]) super[Future].onSuccess(pf)  // Short-circuit if we get a Success

    override def foreach[U](f: T => U)(implicit executor: ExecutionContext): Unit =
      if (!get.isInstanceOf[Failure[T]]) dispatchOrAddCallbacks(new XformPromise[T, Unit](Xform_foreach, f, executor))

    override def flatMap[S](f: T => Future[S])(implicit executor: ExecutionContext): Future[S] = 
      if (!get.isInstanceOf[Failure[T]]) dispatchOrAddCallbacks(new XformPromise[T, S](Xform_flatMap, f, executor))
      else this.asInstanceOf[Future[S]]

    override def map[S](f: T => S)(implicit executor: ExecutionContext): Future[S] =
      if (!get.isInstanceOf[Failure[T]]) dispatchOrAddCallbacks(new XformPromise[T, S](Xform_map, f, executor))
      else this.asInstanceOf[Future[S]]

    override def filter(@deprecatedName('pred) p: T => Boolean)(implicit executor: ExecutionContext): Future[T] =
      if (!get.isInstanceOf[Failure[T]]) super[Future].filter(p) // Short-circuit if we get a Success
      else this

    override def collect[S](pf: PartialFunction[T, S])(implicit executor: ExecutionContext): Future[S] =
      if (!get.isInstanceOf[Failure[T]]) super[Future].collect(pf) // Short-circuit if we get a Success
      else this.asInstanceOf[Future[S]]

    override def recoverWith[U >: T](pf: PartialFunction[Throwable, Future[U]])(implicit executor: ExecutionContext): Future[U] =
      if (!get.isInstanceOf[Success[T]]) dispatchOrAddCallbacks(new XformPromise[T, U](Xform_recoverWith, pf, executor)) // Short-circuit if we get a Failure
      else this.asInstanceOf[Future[U]]

    override def recover[U >: T](pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Future[U] =
      if (!get.isInstanceOf[Success[T]]) dispatchOrAddCallbacks(new XformPromise[T, U](Xform_recover, pf, executor)) // Short-circuit if we get a Failure
      else this.asInstanceOf[Future[U]]

    /* TODO: is this worth overriding?
    override def mapTo[S](implicit tag: scala.reflect.ClassTag[S]): Future[S] =
      if (!get.isInstanceOf[Failure[T]]) super[Future].mapTo[S](tag) // Short-circuit if we get a Success
      else this.asInstanceOf[Future[S]]*/


    override def toString: String = toString0

    @tailrec private final def toString0: String = {
      val state = get()
      if (state.isInstanceOf[Try[T]]) "Future("+state+")"
      else if (state.isInstanceOf[Link[T]]) state.asInstanceOf[Link[T]].promise().toString0
      else /*if (state.isInstanceOf[Callbacks[T]]) */ "Future(<not completed>)"
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
      else /*if (state.isInstanceOf[Callbacks[T]])*/ null
    }

    override final def tryComplete(value: Try[T]): Boolean = {
      val state = get()
      if (state.isInstanceOf[Try[T]]) false
      else /*if (state.isInstanceOf[Link[T]]*/ {
        val r = tryComplete0(state, resolve(value)) // TODO possibly use the resolved value here if tryComplete0 returns true, to unlink with
        if (state.isInstanceOf[Link[T]]) // TODO evaluate efficiency of performing this here vs performing it on root-traversal
          state.asInstanceOf[Link[T]].unlink(this)
        r
      }
    }

    @tailrec
    private final def tryComplete0(state: AnyRef, resolved: Try[T]): Boolean = {
      if (state.isInstanceOf[Callbacks[T]]) {
        if (compareAndSet(state, resolved)) {
          if (state ne Noop) submitWithValue(state.asInstanceOf[Callbacks[T]], resolved) // TODO: if-check needed performance-wise?
          true
        } else tryComplete0(get(), resolved)
      } else if (state.isInstanceOf[Link[T]]) {
        val p = state.asInstanceOf[Link[T]].promise()
        p.tryComplete0(p.get(), resolved) // Use this to get tailcall optimization and avoid re-resolution
      } else /* if(state.isInstanceOf[Try[T]]) */ false
    }

    override final def onComplete[U](func: Try[T] => U)(implicit executor: ExecutionContext): Unit =
      dispatchOrAddCallbacks(new XformPromise[T, Unit](Xform_onComplete, func, executor))

    /** Tries to add the callback, if already completed, it dispatches the callback to be executed.
     *  Used by `onComplete()` to add callbacks to a promise and by `link()` to transfer callbacks
     *  to the root promise when linking two promises together.
     */
    @tailrec private final def dispatchOrAddCallbacks[C <: Callbacks[T]](callbacks: C): C = {
      val state = get()
      if (state.isInstanceOf[Try[T]]) {
        submitWithValue(callbacks, state.asInstanceOf[Try[T]])
        callbacks
      } else if (state.isInstanceOf[Callbacks[T]]) {
        val newCallbacks = if (state eq Noop) callbacks else new ManyCallbacks[T](callbacks, state.asInstanceOf[Callbacks[T]])
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
      } else cb.asInstanceOf[XformPromise[T,_]].submitWithValue(v)
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
        } else if (state.isInstanceOf[Link[T]]) state.asInstanceOf[Link[T]].link(target)
        else /*if (state.isInstanceOf[Callbacks[T]]) */
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
        if (state.isInstanceOf[Callbacks[T]]) {
          if (compareAndSet(state, target)) {
            if (state ne Noop) // TODO: needed?
              promise.dispatchOrAddCallbacks(state.asInstanceOf[Callbacks[T]])
          } else tryLink(get(), target)
        } else linkRootOf(promise) // If the current state is not Callbacks, fall back to normal linking
      }
    }
  }

  // Byte tags for unpacking transformation function inputs or outputs
  final val Xform_map           = 0
  final val Xform_flatMap       = 1
  final val Xform_transform     = 2
  final val Xform_transformWith = 3
  final val Xform_foreach       = 4
  final val Xform_onComplete    = 5
  final val Xform_recover       = 6
  final val Xform_recoverWith   = 7
  final val Xform_filter        = 8
  final val Xform_collect       = 9

    /* Marker trait
   */
  sealed trait Callbacks[-T]

  private[this] final val Noop = new XformPromise[Nothing, Nothing](Int.MinValue, null, InternalCallbackExecutor)

  final class XformPromise[-F, T] private[this] (
    private[this] final var _fun: Any => Any,
    private[this] final var _arg: AnyRef,
    private[this] final val _xform: Byte
  ) extends DefaultPromise[T]() with Callbacks[F] with Runnable with OnCompleteRunnable {
    def this(xform: Int, f: _ => _, ec: ExecutionContext) = this(f.asInstanceOf[Any => Any], ec.prepare(): AnyRef, xform.asInstanceOf[Byte])

    // Gets invoked when a value is available, schedules it to be run():ed by the ExecutionContext
    // submitWithValue *happens-before* run(), through ExecutionContext.execute.
    // Invariant: _arg is `ExecutionContext`
    // requireNonNull(v) will hold as guarded by `resolve`
    final def submitWithValue(v: Try[F]): Unit = 
      if (_fun ne null) { // Noop's, and if already executed: _fun is null
        if (shouldSubmit(v)) submit(v)
        else tryComplete(v.asInstanceOf[Try[T]])
      }

    private[this] final def submit(v: Try[F]): Unit = {
      val executor = _arg.asInstanceOf[ExecutionContext]
      try {
        _arg = v
        executor.execute(this) // Safe publication of _arg = v (and _fun)
      } catch {
        case t if NonFatal(t) =>
          if (!tryFailure(t))
            executor.reportFailure(t)
      }
    }

    private[this] final def shouldSubmit(v: Try[F]): Boolean = {
      val isSuccess = v.isInstanceOf[Success[F]]
      (_xform.asInstanceOf[Int]: @switch) match {
        //case Xform_map | Xform_flatMap | Xform_foreach | Xform_filter | Xform_collect => v.isInstanceOf[Success[F]]
        //case Xform_recover | Xform_recoverWith                                        => v.isInstanceOf[Failure[F]]
        //case Xform_transform | Xform_transformWith | Xform_onComplete | _             => true
        case Xform_map           => isSuccess
        case Xform_flatMap       => isSuccess
        case Xform_transform     => true
        case Xform_transformWith => true
        case Xform_foreach       => isSuccess
        case Xform_onComplete    => true
        case Xform_recover       => !isSuccess
        case Xform_recoverWith   => !isSuccess
        case Xform_filter        => isSuccess
        case Xform_collect       => isSuccess
        case _                   => false
      }
    }

    // Gets invoked by run()
    private[this] final def handle(): Unit =
      (_xform.asInstanceOf[Int]: @switch) match {
        case Xform_map           => doMap()
        case Xform_flatMap       => doFlatMap()
        case Xform_transform     => doTransform()
        case Xform_transformWith => doTransformWith()
        case Xform_foreach       => doForeach()
        case Xform_onComplete    => doOnComplete()
        case Xform_recover       => doRecover()
        case Xform_recoverWith   => doRecoverWith()
        case Xform_filter        => doFilter()
        case Xform_collect       => doCollect()
        case _                   => ()
      }

    // Gets invoked by the ExecutionContext, when we have a value to transform.
    // Invariant: if (_arg.isInstanceOf[Try[F]] && (_fun ne null))
    override final def run(): Unit =
        try handle() catch {
          case t if NonFatal(t) => tryFailure(t)
        } finally { // allow these to GC
          _fun = null
          _arg = null
        }

    override final def toString: String = super[DefaultPromise].toString

    private[this] final def completeFuture(f: Future[T]): Unit = {
      if(f.isInstanceOf[DefaultPromise[T]]) f.asInstanceOf[DefaultPromise[T]].linkRootOf(this)
      else completeWith(f)
    }

    private[this] final def doMap(): Unit = trySuccess(_fun(_arg.asInstanceOf[Success[F]].value).asInstanceOf[T])

    private[this] final def doFlatMap(): Unit = completeFuture(_fun(_arg.asInstanceOf[Success[F]].value).asInstanceOf[Future[T]])

    private[this] final def doTransform(): Unit = tryComplete(_fun(_arg).asInstanceOf[Try[T]])

    private[this] final def doTransformWith(): Unit = completeFuture(_fun(_arg).asInstanceOf[Future[T]])

    private[this] final def doForeach(): Unit = {
      _fun(_arg.asInstanceOf[Success[F]].value)
      tryComplete(Future.successOfUnit.asInstanceOf[Try[T]]) //FIXME
    }

    private[this] final def doOnComplete(): Unit = {
      _fun(_arg)
      tryComplete(Future.successOfUnit.asInstanceOf[Try[T]]) //FIXME
    }

    private[this] final def doRecover(): Unit =
     tryComplete(_arg.asInstanceOf[Try[T]].recover(_fun.asInstanceOf[PartialFunction[Throwable, T]]))

    private[this] final def doRecoverWith(): Unit = {
      val fail =_arg.asInstanceOf[Failure[F]]
      val r = _fun.asInstanceOf[PartialFunction[Throwable, Future[T]]].applyOrElse(fail.exception, Future.recoverWithFailed)
      if (r ne Future.recoverWithFailedMarker) completeFuture(r)
      else tryComplete(fail.asInstanceOf[Failure[T]])
    }

    private[this] final def doFilter(): Unit =
      tryComplete(if(_fun.asInstanceOf[F => Boolean](_arg.asInstanceOf[Success[F]].value)) _arg.asInstanceOf[Try[T]] else Future.filterFailure)

    private[this] final def doCollect(): Unit =
      trySuccess(_fun.asInstanceOf[PartialFunction[F, T]].applyOrElse(_arg.asInstanceOf[Success[F]].value, Future.collectFailed))
  }

  final class ManyCallbacks[-T](final val first: Callbacks[T], final val last: Callbacks[T]) extends Callbacks[T] {
    override final def toString: String = "ManyCallbacks"
  }
}
