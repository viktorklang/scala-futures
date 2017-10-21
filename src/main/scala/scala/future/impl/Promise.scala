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
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.annotation.{ tailrec, switch, unchecked }
import scala.annotation.unchecked.uncheckedVariance
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
      override protected def tryAcquireShared(ignored: Int): Int = if (getState != 0) 1 else -1
      override protected def tryReleaseShared(ignore: Int): Boolean = {
        setState(1)
        true
      }
      override def apply(ignored: Try[T]): Unit = releaseShared(1)
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


  /** Default promise implementation.
   *
   *  A DefaultPromise has three possible states. It can be:
   *
   *  1. Incomplete, with an associated list of callbacks waiting on completion.
   *  2. Complete, with a result.
   *  3. Linked to another DefaultPromise.
   *
   *  If a DefaultPromise is linked to another DefaultPromise, it will
   *  delegate all its operations to that other promise. This means that two
   *  DefaultPromises that are linked will appear, to external callers, to have
   *  exactly the same state and behaviour. For instance, both will appear as
   *  incomplete, or as complete with the same result value.
   *
   *  A DefaultPromise stores its state entirely in the AnyRef cell exposed by
   *  AtomicReference. The type of object stored in the cell fully describes the
   *  current state of the promise.
   *
   *  1. Callbacks - The promise is incomplete and has zero or more callbacks
   *     to call when it is eventually completed.
   *  2. Try[T] - The promise is complete and now contains its value.
   *  3. DefaultPromise[T] - The promise is linked to another promise.
   *
   * The ability to link DefaultPromises is needed to prevent memory leaks when
   * using Future.flatMap. The previous implementation of Future.flatMap used
   * onComplete handlers to propagate the ultimate value of a flatMap operation
   * to its promise. Recursive calls to flatMap built a chain of onComplete
   * handlers and promises. Unfortunately none of the handlers or promises in
   * the chain could be collected until the handlers had been called and
   * detached, which only happened when the final flatMap future was completed.
   * (In some situations, such as infinite streams, this would never actually
   * happen.) Because of the fact that the promise implementation internally
   * created references between promises, and these references were invisible to
   * user code, it was easy for user code to accidentally build large chains of
   * promises and thereby leak memory.
   *
   * The problem of leaks is solved by automatically breaking these chains of
   * promises, so that promises don't refer to each other in a long chain. This
   * allows each promise to be individually collected. The idea is to "flatten"
   * the chain of promises, so that instead of each promise pointing to its
   * neighbour, they instead point directly the promise at the root of the
   * chain. This means that only the root promise is referenced, and all the
   * other promises are available for garbage collection as soon as they're no
   * longer referenced by user code.
   *
   * To make the chains flattenable, the concept of linking promises together
   * needed to become an explicit feature of the DefaultPromise implementation,
   * so that the implementation to navigate and rewire links as needed. The idea
   * of linking promises is based on the [[Twitter promise implementation
   * https://github.com/twitter/util/blob/master/util-core/src/main/scala/com/twitter/util/Promise.scala]].
   *
   * In practice, flattening the chain cannot always be done perfectly. When a
   * promise is added to the end of the chain, it scans the chain and links
   * directly to the root promise. This prevents the chain from growing forwards
   * But the root promise for a chain can change, causing the chain to grow
   * backwards, and leaving all previously-linked promise pointing at a promise
   * which is no longer the root promise.
   *
   * To mitigate the problem of the root promise changing, whenever a promise's
   * methods are called, and it needs a reference to its root promise it calls
   * the `promise()` method on its Link. This method re-scans the promise chain to
   * get the root promise, and also compresses its links so that it links
   * directly to whatever the current root promise is. This ensures that the
   * chain is flattened whenever `promise()` is called. And since
   * `promise()` is called at every possible opportunity (when getting a
   * promise's value, when adding an onComplete handler, etc), this will happen
   * frequently. Unfortunately, even this eager relinking doesn't absolutely
   * guarantee that the chain will be flattened and that leaks cannot occur.
   * However eager relinking does greatly reduce the chance that leaks will
   * occur.
   *
   * Future.flatMap/recoverWith/transformWith links DefaultPromises together by calling the `linkRootOf`
   * method. This is the only externally visible interface to linked DefaultPromises.
   */
  // Left non-final to enable addition of extra fields by Java/Scala converters in scala-java8-compat.
  class DefaultPromise[T] extends AtomicReference[AnyRef](NoopCallback: AnyRef) with scala.future.Promise[T] with scala.future.Future[T] {
    /**
     * Constructs a new, completed, Promise.
     */
    def this(result: Try[T]) = {
      this() // TODO: avoid the initial NoopCallback write and directly write the resolve(result)
      set(resolve(result))
    }

    /**
     * Returns the associaed `Future` with this `Promise`
     */
    override def future: Future[T] = this

    override def transform[S](f: Try[T] => Try[S])(implicit executor: ExecutionContext): Future[S] =
      dispatchOrAddCallbacks(new TransformPromise(f, executor.prepare())).future

    override def transformWith[S](f: Try[T] => Future[S])(implicit executor: ExecutionContext): Future[S] =
      dispatchOrAddCallbacks(new TransformWithPromise(f, executor.prepare())).future

    override def onFailure[U](@deprecatedName('callback) pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Unit =
      if (!value0.isInstanceOf[Success[T]]) super[Future].onFailure(pf)

    override def onSuccess[U](pf: PartialFunction[T, U])(implicit executor: ExecutionContext): Unit = 
      if (!value0.isInstanceOf[Failure[T]]) super[Future].onSuccess(pf)

    override def foreach[U](f: T => U)(implicit executor: ExecutionContext): Unit =
      if (!value0.isInstanceOf[Failure[T]]) super[Future].foreach(f)

    override def flatMap[S](f: T => Future[S])(implicit executor: ExecutionContext): Future[S] = 
      if (!value0.isInstanceOf[Failure[T]]) super[Future].flatMap(f)
      else this.asInstanceOf[Future[S]]

    override def map[S](f: T => S)(implicit executor: ExecutionContext): Future[S] = {
      if (!value0.isInstanceOf[Failure[T]]) dispatchOrAddCallbacks(new MapPromise(f, executor.prepare()))
      else this.asInstanceOf[Future[S]]
    }

    override def filter(@deprecatedName('pred) p: T => Boolean)(implicit executor: ExecutionContext): Future[T] =
      if (!value0.isInstanceOf[Failure[T]]) super[Future].filter(p)
      else this

    override def collect[S](pf: PartialFunction[T, S])(implicit executor: ExecutionContext): Future[S] =
      if (!value0.isInstanceOf[Failure[T]]) super[Future].collect(pf)
      else this.asInstanceOf[Future[S]]

    override def recoverWith[U >: T](pf: PartialFunction[Throwable, Future[U]])(implicit executor: ExecutionContext): Future[U] =
      if (!value0.isInstanceOf[Success[T]]) super[Future].recoverWith(pf)
      else this.asInstanceOf[Future[U]]

    override def recover[U >: T](pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Future[U] =
      if (!value0.isInstanceOf[Success[T]]) super[Future].recover(pf)
      else this.asInstanceOf[Future[U]]

    override def mapTo[S](implicit tag: scala.reflect.ClassTag[S]): Future[S] =
      if (!value0.isInstanceOf[Failure[T]]) super[Future].mapTo[S](tag)
      else this.asInstanceOf[Future[S]]

    override def toString: String = toString0

    @tailrec private final def toString0: String = {
      val state = get()
      if (state.isInstanceOf[Try[T]]) "Future("+state+")"
      else if (state.isInstanceOf[Link[T]]) state.asInstanceOf[Link[T]].promise().toString0
      else /*if (state.isInstanceOf[Callbacks[T]]) */ "Future(<not completed>)"
    }

    /** Try waiting for this promise to be completed.
     */
    protected final def tryAwait(atMost: Duration): Boolean = if (!isCompleted) {
      import Duration.Undefined
      atMost match {
        case e if e eq Undefined => throw new IllegalArgumentException("cannot wait for Undefined period")
        case Duration.Inf        =>
          val l = new CompletionLatch[T]()
          onComplete(l)(InternalCallbackExecutor)
          l.acquireSharedInterruptibly(1)
        case Duration.MinusInf   => // Drop out
        case f: FiniteDuration   =>
          if (f > Duration.Zero) {
            val l = new CompletionLatch[T]()
            onComplete(l)(InternalCallbackExecutor)
            l.tryAcquireSharedNanos(1, f.toNanos)
          }
      }

      isCompleted
    } else true // Already completed

    @throws(classOf[TimeoutException])
    @throws(classOf[InterruptedException])
    final def ready(atMost: Duration)(implicit permit: CanAwait): this.type =
      if (tryAwait(atMost)) this
      else throw new TimeoutException("Future timed out after [" + atMost + "]")

    @throws(classOf[Exception])
    final def result(atMost: Duration)(implicit permit: CanAwait): T = {
      ready(atMost)
      value0.get // ready throws TimeoutException if timeout so value0.get is safe here
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

    private[this] final def resolve(value: Try[T]): Try[T] =
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

    override final def tryComplete(value: Try[T]): Boolean = {
      val state = get()
      if (state.isInstanceOf[Try[T]]) false
      else if (state.isInstanceOf[Callbacks[T]]) tryComplete0(state, resolve(value))
      else /*if (state.isInstaceOf[Link[T]]*/ {
        val r = tryComplete0(state, resolve(value))
        state.asInstanceOf[Link[T]].unlink(this)
        r
      }
    }

    @tailrec
    private final def tryComplete0(state: AnyRef, resolved: Try[T]): Boolean =
      if (state.isInstanceOf[Try[T]]) false
      else if (state.isInstanceOf[Callbacks[T]]) {
        if (compareAndSet(state, resolved)) {
          state.asInstanceOf[Callbacks[T]].submitWithValue(resolved)
          true
        } else tryComplete0(get(), resolved)
      }
      else /*if (state.isInstanceOf[Link[T]])*/ {
        val p = state.asInstanceOf[Link[T]].promise()
        p.tryComplete0(p.get(), resolved)
      }

    override final def onComplete[U](func: Try[T] => U)(implicit executor: ExecutionContext): Unit =
      dispatchOrAddCallbacks(new EffectPromise(func, executor.prepare()))

    /** Tries to add the callback, if already completed, it dispatches the callback to be executed.
     *  Used by `onComplete()` to add callbacks to a promise and by `link()` to transfer callbacks
     *  to the root promise when linking two promises together.
     */
    @tailrec private final def dispatchOrAddCallbacks[C <: Callbacks[T]](callbacks: C): C = {
      val state = get()
      if (state.isInstanceOf[Try[T]]) callbacks.submitWithValue(state.asInstanceOf[Try[T]])
      else if (state.isInstanceOf[Callbacks[T]]) {
        if(compareAndSet(state, state.asInstanceOf[Callbacks[T]] prepend callbacks)) callbacks
        else dispatchOrAddCallbacks(callbacks)
      } else /*if (state.isInstanceOf[Link[T]])*/
        state.asInstanceOf[Link[T]].promise().dispatchOrAddCallbacks(callbacks)
    }

    /** Link this promise to the root of another promise using `link()`. Should only be
     *  be called by transformWith.
     */
    protected[future] final def linkRootOf(target: DefaultPromise[T]): Unit = link(new Link(target))

    /** Link this promise to another promise so that both promises share the same
     *  externally-visible state. Depending on the current state of this promise, this
     *  may involve different things. For example, any onComplete listeners will need
     *  to be transferred.
     *
     *  If this promise is already completed, then the same effect as linking -
     *  sharing the same completed value - is achieved by simply sending this
     *  promise's result to the target promise.
     */
    @tailrec
    private[this] final def link(target: Link[T]): Unit = {
      val promise = target.promise()
      if (this ne promise) {
        val state = get()
        if (state.isInstanceOf[Try[T]]) {
          if (!promise.tryComplete(state.asInstanceOf[Try[T]]))
            throw new IllegalStateException("Cannot link completed promises together")
        } else if (state.isInstanceOf[Link[T]]) state.asInstanceOf[Link[T]].link(target)
        else /*if (state.isInstanceOf[Callbacks[T]]) */ {
          if (compareAndSet(state, target)) {
            if (state ne NoopCallback)
              promise.dispatchOrAddCallbacks(state.asInstanceOf[Callbacks[T]])
          } else link(target)
        }
      }
    }
  }

  sealed abstract class XformCallback[+F, T] extends DefaultPromise[T]() with Callbacks[F] with Runnable with OnCompleteRunnable {
    override final def prepend[U >: F](c: Callbacks[U]): Callbacks[U] =
      if (c.isInstanceOf[XformCallback[U,_]]) new ManyCallbacks(c.asInstanceOf[XformCallback[U,_]], this)
      else if (c.isInstanceOf[ManyCallbacks[U]]) c.asInstanceOf[ManyCallbacks[U]].append(this)
      else /*if (c eq Promise.NoopCallback)*/ this

    override final def toString: String = super[DefaultPromise].toString
  }

  sealed abstract class XformPromise[F, FM[_], TM[_], T](f: FM[F] => TM[T], ec: ExecutionContext) extends XformCallback[F, T] {
    private[this] final var _arg: AnyRef = ec // Is first the EC (needs to be pre-prepared) -> then the value -> then null
    private[this] final var _fun: FM[F] => TM[T] = f // Is first the transformation function -> then null

    // Gets invoked when a value is available, schedules it to be run():ed by the ExecutionContext
    // submitWithValue *happens-before* run(), through ExecutionContext.execute.
    override final def submitWithValue(v: Try[F @uncheckedVariance]): this.type = {
      val a = _arg
      if (a.isInstanceOf[ExecutionContext]) {
        val executor = a.asInstanceOf[ExecutionContext]
        _arg = requireNonNull(v)
        try executor.execute(this) catch { case NonFatal(t) => executor reportFailure t }
      }
      this
    }

    // Gets invoked by run(), runs the transformation and stores the result
    def transform(v: Try[F], fn: FM[F] => TM[T]): Unit

    // Gets invoked by the ExecutionContext, when we have a value to transform.
    // *Happens-before* 
    override final def run(): Unit = {
      val v = _arg
      _arg = null
      if (v.isInstanceOf[Try[F]]) {
        val fun = _fun
        _fun = null
        try transform(v.asInstanceOf[Try[F]], fun) catch {
          case NonFatal(t) => this failure t
        }
      }
    }
  }

  final class MapPromise[F, T](f: F => T, ec: ExecutionContext) extends XformPromise[F, ({type Id[a] = a})#Id, ({type Id[a] = a})#Id, T](f, ec) {
    override def transform(v: Try[F], fn: F => T): Unit = this complete { v map fn }
  }

  final class TransformPromise[F, T](f: Try[F] => Try[T], ec: ExecutionContext) extends XformPromise[F, Try, Try, T](f, ec) {
    override def transform(v: Try[F], fn: Try[F] => Try[T]): Unit = this complete fn(v)
  }

  final class TransformWithPromise[F, T](f: Try[F] => Future[T], ec: ExecutionContext) extends XformPromise[F, Try, Future, T](f, ec) {
    override def transform(v: Try[F], fn: Try[F] => Future[T]): Unit = {
      val r = fn(v)
      if (r.isInstanceOf[DefaultPromise[T]]) r.asInstanceOf[DefaultPromise[T]].linkRootOf(this)
      else this completeWith r
    }
  }

  final class EffectPromise[F](f: Try[F] => Any, ec: ExecutionContext) extends XformPromise[F, Try, ({type Id[a] = Any})#Id, Any](f, ec) {
    override def transform(v: Try[F], fn: Try[F] => Any): Unit = {
      fn(v)
      this.success(())
    }
  }

  /* Encodes the concept of having callbacks.
   * This is an `abstract class` to make sure calls are `invokevirtual` rather than `invokeinterface`
   */
  sealed trait Callbacks[+T] {
    def prepend[U >: T](c: Callbacks[U]): Callbacks[U]
    def submitWithValue(v: Try[T @uncheckedVariance]): this.type
  }

  /* Represents 0 Callbacks, is used as an initial, sentinel, value for DefaultPromise
   * This used to be a `case object` but in order to keep `Callbacks`'s methods bimorphic it was reencoded as `val`
   */
  final object NoopCallback extends Callbacks[Nothing] {
    override final def prepend[U >: Nothing](c: Callbacks[U]): Callbacks[U] = c
    override final def submitWithValue(v: Try[Nothing]): this.type = this
    override final def toString: String = "Noop"
  }

  // `p` is always either a ManyCallbacks or an XformCallback
  // `next` is always either a ManyCallbacks or an XformCallback
  final class ManyCallbacks[+T] private[ManyCallbacks](final val p: Callbacks[T], final val next: Callbacks[T]) extends Callbacks[T] {
    final def this(second: XformCallback[T,_], first: XformCallback[T,_]) =
      this(second: Callbacks[T], first: Callbacks[T])

    override final def prepend[U >: T](c: Callbacks[U]): Callbacks[U] =
      if (c.isInstanceOf[XformCallback[U,_]]) prepend(c.asInstanceOf[XformCallback[U,_]])
      else if (c.isInstanceOf[ManyCallbacks[U]]) {
        val mc = c.asInstanceOf[ManyCallbacks[U]]
        new ManyCallbacks(mc, this)
      } else /* if (c eq NoopCallback) */this

    final def prepend[U >: T](tp: XformCallback[U,_]): ManyCallbacks[U] = 
      new ManyCallbacks(tp, this)

    final def append[U >: T](tp: XformCallback[U,_]): ManyCallbacks[U] =
      new ManyCallbacks(this, tp)

    final def concat[U >: T](m: ManyCallbacks[U]): ManyCallbacks[U] =
      new ManyCallbacks(this, m)

    override final def submitWithValue(v: Try[T @uncheckedVariance]): this.type =
      submitWithValue(this, v)

    @tailrec private[this] final def submitWithValue(cb: Callbacks[T], v: Try[T]): this.type =
      if (cb.isInstanceOf[ManyCallbacks[T]]) {
        val m = cb.asInstanceOf[ManyCallbacks[T]]
        m.p.submitWithValue(v) // TODO: this will grow the stack—needs real-world proofing
        submitWithValue(m.next, v)
      } else {
        cb.submitWithValue(v)
        this
      }

    override final def toString: String = "ManyCallbacks"
  }
}
