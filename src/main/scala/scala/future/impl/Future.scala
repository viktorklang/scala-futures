/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.future.impl

import scala.util.control.{ NoStackTrace }
import scala.util.{ Try, Success, Failure }
import scala.collection.mutable.Builder
import scala.language.higherKinds

private[future] final object Future {
  /**
   * Utilities, hoisted functions, etc.
   */

  final val toBoxed = Map[Class[_], Class[_]](
    classOf[Boolean] -> classOf[java.lang.Boolean],
    classOf[Byte]    -> classOf[java.lang.Byte],
    classOf[Char]    -> classOf[java.lang.Character],
    classOf[Short]   -> classOf[java.lang.Short],
    classOf[Int]     -> classOf[java.lang.Integer],
    classOf[Long]    -> classOf[java.lang.Long],
    classOf[Float]   -> classOf[java.lang.Float],
    classOf[Double]  -> classOf[java.lang.Double],
    classOf[Unit]    -> classOf[scala.runtime.BoxedUnit]
  )

  private[this] final val _cachedId: AnyRef => AnyRef = Predef.identity _

  final def id[T]: T => T = _cachedId.asInstanceOf[T => T]

  final val collectFailed =
    (t: Any) => throw new NoSuchElementException("Future.collect partial function is not defined at: " + t) with NoStackTrace

  final val filterFailure =
    Failure[Nothing](new NoSuchElementException("Future.filter predicate is not satisfied") with NoStackTrace)

  final val failedFailure =
    Failure[Nothing](new NoSuchElementException("Future.failed not completed with a throwable.") with NoStackTrace)

  final val recoverWithFailedMarker =
    scala.future.Future.failed[Nothing](new Throwable with NoStackTrace)

  final val recoverWithFailed =
    (t: Throwable) => recoverWithFailedMarker

  final def firstCompletedOfException =
    new NoSuchElementException("Future.firstCompletedOf empty collection")

  private[this] final val _zipWithTuple2: (Any, Any) => (Any, Any) = Tuple2.apply _
  final def zipWithTuple2[T,U] = _zipWithTuple2.asInstanceOf[(T,U) => (T,U)]

  private[this] final val _addToBuilderFun: (Builder[Any, Nothing], Any) => Builder[Any, Nothing] = (b: Builder[Any, Nothing], e: Any) => b += e
  final def addToBuilderFun[A, M[_]] =  _addToBuilderFun.asInstanceOf[Function2[Builder[A, M[A]], A, Builder[A, M[A]]]]

  final val successOfUnit: Success[Unit] = Success(())
  //---------------------------------------------------------------------------//

  /*def onSuccess[U](pf: PartialFunction[T, U])(implicit executor: ExecutionContext): Unit = onComplete {
    t => if (t.isInstanceOf[Success[T]]) pf.applyOrElse[T, Any](t.asInstanceOf[Success[T]].value, Future.id[T])
  }

  def onFailure[U](pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Unit = onComplete {
      t => if(t.isInstanceOf[Failure[T]]) pf.applyOrElse[Throwable, Any](t.asInstanceOf[Failure[T]].exception, Future.id[Throwable])
  }


  def failed: Future[Throwable] =
    transform({
      t =>
        if (t.isInstanceOf[Failure[T]]) Success(t.asInstanceOf[Failure[T]].exception)
        else Future.failedFailure
    })(internalExecutor)

  def foreach[U](f: T => U)(implicit executor: ExecutionContext): Unit = onComplete { _ foreach f }

  def transform[S](s: T => S, f: Throwable => Throwable)(implicit executor: ExecutionContext): Future[S] =
    transform {
      t => 
        if (t.isInstanceOf[Success[T]]) t map s
        else throw f(t.asInstanceOf[Failure[T]].exception) // will throw fatal errors!
    }

  def map[S](f: T => S)(implicit executor: ExecutionContext): Future[S] = transform(_ map f)

  def flatMap[S](f: T => Future[S])(implicit executor: ExecutionContext): Future[S] = transformWith {
    t => 
      if(t.isInstanceOf[Success[T]]) f(t.asInstanceOf[Success[T]].value)
      else this.asInstanceOf[Future[S]] // Safe cast
  }

  def flatten[S](implicit ev: T <:< Future[S]): Future[S] = flatMap(ev)(internalExecutor)

  def filter(@deprecatedName('pred) p: T => Boolean)(implicit executor: ExecutionContext): Future[T] =
    transform {
      t =>
        if (t.isInstanceOf[Success[T]]) {
          if (p(t.asInstanceOf[Success[T]].value)) t
          else Future.filterFailure
        } else t
    }

  def collect[S](pf: PartialFunction[T, S])(implicit executor: ExecutionContext): Future[S] =
    transform {
      t =>
        if (t.isInstanceOf[Success[T]])
          Success(pf.applyOrElse(t.asInstanceOf[Success[T]].value, Future.collectFailed))
        else t.asInstanceOf[Failure[S]]
    }

  def recover[U >: T](pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Future[U] =
    transform { _ recover pf }

  def recoverWith[U >: T](pf: PartialFunction[Throwable, Future[U]])(implicit executor: ExecutionContext): Future[U] =
    transformWith {
      t =>
        if (t.isInstanceOf[Failure[T]]) {
          val result = pf.applyOrElse(t.asInstanceOf[Failure[T]].exception, Future.recoverWithFailed)
          if (result ne Future.recoverWithFailedMarker) result
          else this
        } else this
    }

  def zipWith[U, R](that: Future[U])(f: (T, U) => R)(implicit executor: ExecutionContext): Future[R] =
    flatMap(r1 => that.map(r2 => f(r1, r2)))(internalExecutor)

  def fallbackTo[U >: T](that: Future[U]): Future[U] =
    if (this eq that) this
    else {
      implicit val ec = internalExecutor
      transformWith {
        t =>
          if (t.isInstanceOf[Success[T]]) this
          else that transform { tt => if (tt.isInstanceOf[Success[U]]) tt else t }
      }
    }

  def mapTo[S](implicit tag: ClassTag[S]): Future[S] = {
    implicit val ec = internalExecutor
    val boxedClass = {
      val c = tag.runtimeClass
      if (c.isPrimitive) Future.toBoxed(c) else c
    }
    require(boxedClass ne null)
    map(s => boxedClass.cast(s).asInstanceOf[S])
  }

  def andThen[U](pf: PartialFunction[Try[T], U])(implicit executor: ExecutionContext): Future[T] =
    transform {
      result =>
        try pf.applyOrElse[Try[T], Any](result, Future.id[Try[T]])
        catch { case t if NonFatal(t) => executor.reportFailure(t) }

        result
    }*/
}
