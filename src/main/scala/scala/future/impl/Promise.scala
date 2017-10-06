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

      final def link(to: DefaultPromise[T]): DefaultPromise[T] = {
        val target = to.get()
        if (target.isInstanceOf[Link[T]]) link(target.asInstanceOf[Link[T]].promise())
        else {
          val current = get()
          if (compareAndSet(current, to)) to
          else link(to)
        }
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
   * the `compressedRoot()` method. This method re-scans the promise chain to
   * get the root promise, and also compresses its links so that it links
   * directly to whatever the current root promise is. This ensures that the
   * chain is flattened whenever `compressedRoot()` is called. And since
   * `compressedRoot()` is called at every possible opportunity (when getting a
   * promise's value, when adding an onComplete handler, etc), this will happen
   * frequently. Unfortunately, even this eager relinking doesn't absolutely
   * guarantee that the chain will be flattened and that leaks cannot occur.
   * However eager relinking does greatly reduce the chance that leaks will
   * occur.
   *
   * Future.flatMap links DefaultPromises together by calling the `linkRootOf`
   * method. This is the only externally visible interface to linked
   * DefaultPromises, and `linkedRootOf` is currently only designed to be called
   * by Future.flatMap.
   */
  // Left non-final to enable addition of extra fields by Java/Scala converters in scala-java8-compat.
  class DefaultPromise[T] extends AtomicReference[AnyRef](NoopCallback: AnyRef) with scala.future.Promise[T] with scala.future.Future[T] {
    /**
     * Constructs a new, completed, Promise.
     */
    def this(result: Try[T]) = {
      this()
      set(resolve(result))
    }

    /**
     * Returns the associaed `Future` with this `Promise`
     */
    override def future: Future[T] = this

    override def transform[S](f: Try[T] => Try[S])(implicit executor: ExecutionContext): Future[S] = {
      val p = new TransformationalPromise(f, executor.prepare())
      dispatchOrAddCallbacks(p)
      p.future
    }

    override def transformWith[S](f: Try[T] => Future[S])(implicit executor: ExecutionContext): Future[S] = {
      val p = new TransformationalPromise(f, executor.prepare())
      dispatchOrAddCallbacks(p)
      p.future
    }

    @inline private[this] final def coerce[F[_],A,B](f: F[A]): F[B] = f.asInstanceOf[F[B]]

    private[this] final def failureOrUnknown: Boolean = {
      val v = value0
      ((v eq null) || v.isInstanceOf[Failure[T]])
    }

    private[this] final def successOrUnknown: Boolean = {
      val v = value0
      ((v eq null) || v.isInstanceOf[Success[T]])
    }

    override def onFailure[U](@deprecatedName('callback) pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Unit =
      if (failureOrUnknown) super[Future].onFailure(pf)

    override def onSuccess[U](pf: PartialFunction[T, U])(implicit executor: ExecutionContext): Unit = 
      if (successOrUnknown) super[Future].onSuccess(pf)

    override def foreach[U](f: T => U)(implicit executor: ExecutionContext): Unit =
      if (successOrUnknown) super[Future].foreach(f)

    override def flatMap[S](f: T => Future[S])(implicit executor: ExecutionContext): Future[S] = 
      if (successOrUnknown) super[Future].flatMap(f)
      else coerce(this)

    override def map[S](f: T => S)(implicit executor: ExecutionContext): Future[S] =
      if (successOrUnknown) super[Future].map(f)
      else coerce(this)

    override def filter(@deprecatedName('pred) p: T => Boolean)(implicit executor: ExecutionContext): Future[T] =
      if (successOrUnknown) super[Future].filter(p)
      else coerce(this)

    override def collect[S](pf: PartialFunction[T, S])(implicit executor: ExecutionContext): Future[S] =
      if (successOrUnknown) super[Future].collect(pf)
      else coerce(this)

    override def recoverWith[U >: T](pf: PartialFunction[Throwable, Future[U]])(implicit executor: ExecutionContext): Future[U] =
      if (failureOrUnknown) super[Future].recoverWith(pf)
      else coerce(this)

    override def recover[U >: T](pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Future[U] =
      if (failureOrUnknown) super[Future].recover(pf)
      else coerce(this)

    override def mapTo[S](implicit tag: scala.reflect.ClassTag[S]): Future[S] =
      if (successOrUnknown) super[Future].mapTo[S](tag)
      else coerce(this)

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

    override final def tryComplete(value: Try[T]): Boolean =
      tryComplete0(value, false)

    @tailrec
    private final def tryComplete0(v: Try[T], resolved: Boolean): Boolean = {
      val state = get()
      if (state.isInstanceOf[Try[T]]) false
      else if (state.isInstanceOf[Callbacks[T]]) {
        val rv = if (resolved) v else resolve(v)
        if (compareAndSet(state, rv)) {
          submitWithValue(state.asInstanceOf[Callbacks[T]], rv)
          true
        } else tryComplete0(rv, true)
      }
      else /*if (state.isInstanceOf[Link[T]])*/
        state.asInstanceOf[Link[T]].promise().tryComplete0(v, resolved)
    }

    override final def onComplete[U](func: Try[T] => U)(implicit executor: ExecutionContext): Unit =
      dispatchOrAddCallbacks(new TransformationalPromise[T,({type Id[a] = a})#Id,U](func, executor.prepare()))

    /** Tries to add the callback, if already completed, it dispatches the callback to be executed.
     *  Used by `onComplete()` to add callbacks to a promise and by `link()` to transfer callbacks
     *  to the root promise when linking two promises together.
     */
    @tailrec private final def dispatchOrAddCallbacks(callbacks: Callbacks[T]): Unit = {
      val state = get()
      if (state.isInstanceOf[Try[T]]) submitWithValue(callbacks, state.asInstanceOf[Try[T]])
      else if (state.isInstanceOf[Callbacks[T]]) {
        if(compareAndSet(state, state.asInstanceOf[Callbacks[T]] prepend callbacks)) ()
        else dispatchOrAddCallbacks(callbacks)
      } else /*if (state.isInstanceOf[Link[T]])*/
        state.asInstanceOf[Link[T]].promise().dispatchOrAddCallbacks(callbacks)
    }

    private[this] final def submitWithValue(c: Callbacks[T], v: Try[T]): Unit = {
       if (c.isInstanceOf[TransformationalPromise[T,({type Id[a] = a})#Id,_]])
         c.asInstanceOf[TransformationalPromise[T,({type Id[a] = a})#Id,_]].submitWithValue(v)
       else if (c.isInstanceOf[ManyCallbacks[T]]) c.asInstanceOf[ManyCallbacks[T]].submitWithValue(v)
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
        } else if (state.isInstanceOf[Link[T]]) state.asInstanceOf[Link[T]].link(promise)
        else /*if (state.isInstanceOf[Callbacks[T]]) */ {
          if (compareAndSet(state, target)) promise.dispatchOrAddCallbacks(state.asInstanceOf[Callbacks[T]])
          else link(target)
        }
      }
    }
  }

  sealed abstract class AbstractTransformationalPromise[+F, T] extends DefaultPromise[T]() with Callbacks[F] with Runnable with OnCompleteRunnable {
    override final def prepend[U >: F](c: Callbacks[U]): Callbacks[U] =
      if (c.isInstanceOf[ManyCallbacks[U]]) c.asInstanceOf[ManyCallbacks[U]].append(this)
      else if (c eq Promise.NoopCallback) this
      else new ManyCallbacks(c.asInstanceOf[AbstractTransformationalPromise[U,_]], this)

    def submitWithValue(v: Try[F @uncheckedVariance]): Unit

    override final def toString: String = super[DefaultPromise].toString
  }

  final class TransformationalPromise[F, M[_], T](f: Try[F] => M[T], ec: ExecutionContext) extends AbstractTransformationalPromise[F, T] {
    private[this] final var _arg: AnyRef = ec // Is first the EC (needs to be pre-prepared) -> then the value -> then null
    private[this] final var _fun: Try[F] => M[T] = f // Is first the transformation function -> then null

    // Gets invoked when a value is available, schedules it to be run():ed by the ExecutionContext
    override final def submitWithValue(v: Try[F @uncheckedVariance]): Unit = {
      val a = _arg
      if (a.isInstanceOf[ExecutionContext]) {
        val executor = a.asInstanceOf[ExecutionContext]
        _arg = v
        try executor.execute(this) catch { case NonFatal(t) => executor reportFailure t }
      }
    }

    // Gets invoked by run(), runs the transformation and stores the result
    private[this] final def transform(v: Try[F], fn: Try[F] => M[T]): Unit = {
      val res = fn(v)
      if (res.isInstanceOf[Try[T @unchecked]]) this complete res.asInstanceOf[Try[T]]
      else if (res.isInstanceOf[DefaultPromise[T @unchecked]]) res.asInstanceOf[DefaultPromise[T]].linkRootOf(this)
      else if (res.isInstanceOf[Future[T @unchecked]]) this completeWith res.asInstanceOf[Future[T]]
      else this success res.asInstanceOf[T]
    }

    // Gets invoked by the ExecutionContext, when we have a value to transform
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

  /* Encodes the concept of having callbacks.
   * This is an `abstract class` to make sure calls are `invokevirtual` rather than `invokeinterface`
   */
  sealed trait Callbacks[+T] {
    /* Logically prepends the callback `c` onto `this` callback */
    def prepend[U >: T](c: Callbacks[U]): Callbacks[U]
  }

  /* Represents 0 Callbacks, is used as an initial, sentinel, value for DefaultPromise
   * This used to be a `case object` but in order to keep `Callbacks`'s methods bimorphic it was reencoded as `val`
   */
  final object NoopCallback extends Callbacks[Nothing] {
    override def prepend[U >: Nothing](c: Callbacks[U]): Callbacks[U] = c
    override def toString: String = "Noop"
  }

  final class ManyCallbacks[+T] private[ManyCallbacks](
    final val p: AbstractTransformationalPromise[T,_],
    final val next: Callbacks[T],
    final val size: Int) extends Callbacks[T] {

    final def this(first: AbstractTransformationalPromise[T,_]) =
      this(first, NoopCallback, 1)

    final def this(second: AbstractTransformationalPromise[T,_], first: AbstractTransformationalPromise[T,_]) =
      this(second, first, 2)

    override final def prepend[U >: T](c: Callbacks[U]): Callbacks[U] =
      if (c.isInstanceOf[AbstractTransformationalPromise[U,_]]) prepend(c.asInstanceOf[AbstractTransformationalPromise[U,_]])
      else if (c.isInstanceOf[ManyCallbacks[U]]) c.asInstanceOf[ManyCallbacks[U]].concat(this)
      else /* if (c eq NoopCallback) */this

    final def prepend[U >: T](tp: AbstractTransformationalPromise[U,_]): ManyCallbacks[U] = 
      new ManyCallbacks(tp, this, size + 1)

    final def append[U >: T](tp: AbstractTransformationalPromise[U,_]): ManyCallbacks[U] =
      this concat new ManyCallbacks(tp)

    final def concat[U >: T](m: ManyCallbacks[U]): ManyCallbacks[U] = {
      @tailrec def toBuf(buf: Array[AbstractTransformationalPromise[T,_]], pos: Int, cur: ManyCallbacks[T]): Array[AbstractTransformationalPromise[T,_]] =
        if (pos < size) {
          buf(pos) = cur.p
          val cont = cur.next
          if (cont.isInstanceOf[ManyCallbacks[T]]) toBuf(buf, pos + 1, cont.asInstanceOf[ManyCallbacks[T]])
          else if (cont.isInstanceOf[AbstractTransformationalPromise[T,_]]) {
            buf(pos + 1) = cont.asInstanceOf[AbstractTransformationalPromise[T,_]]
            buf
          } else buf
        } else buf
        
        @tailrec def fromBuf(buf: Array[AbstractTransformationalPromise[T,_]], pos: Int, cbs: ManyCallbacks[U]): ManyCallbacks[U] =
          if (pos >= 0) fromBuf(buf, pos - 1, cbs.prepend(buf(pos))) else cbs

        fromBuf(toBuf(new Array[AbstractTransformationalPromise[T,_]](size), 0, this), size, m)
    }

    final def submitWithValue(v: Try[T @uncheckedVariance]): Unit = submitWithValue(this, v)

    @tailrec private[this] final def submitWithValue(cb: Callbacks[T], v: Try[T]): Unit =
      if (cb.isInstanceOf[AbstractTransformationalPromise[T,_]]) cb.asInstanceOf[AbstractTransformationalPromise[T,_]].submitWithValue(v)
      else if (cb.isInstanceOf[ManyCallbacks[T]]) {
        val m = cb.asInstanceOf[ManyCallbacks[T]]
        m.p.submitWithValue(v)
        submitWithValue(m.next, v)
      } /* else if (c eq NoopCallback) ()*/

    override final def toString: String = s"Callbacks($p, $next)"
  }
}
