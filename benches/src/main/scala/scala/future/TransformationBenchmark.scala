package scala.future

import scala.concurrent.duration._
import java.util.concurrent.{TimeUnit,Executor, ExecutorService}
import org.openjdk.jmh.annotations._
import scala.util.Try
import scala.annotation.tailrec
import scala.{concurrent => stdlib}
import scala.{future => improved}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1000)
@Measurement(iterations = 10000)
@Fork(value = 1, jvmArgsAppend = Array("-ea","-server","-XX:+UseCompressedOops","-XX:+AggressiveOpts","-XX:+AlwaysPreTouch", "-XX:+UseCondCardMark"))
abstract class AbstractBaseBenchmark {

  @Param(Array[String]("fjp", "fix", "fie"))
  var pool: String = _

  @Param(Array[String]("1"))
  var threads: Int = _

  @Param(Array[String]("pre","post"))
  var completed: String = _

  var isCompleted: Boolean = _

  var executor: Executor = _

  var stdLibEC: stdlib.ExecutionContext = _
  var improvedEC: stdlib.ExecutionContext = _

  val timeout = 60.seconds

  @Setup(Level.Trial)
  final def startup: Unit = {
    val (executorStdlib, executorImproved) = pool match {
      case "fjp" =>
        val fjp = new java.util.concurrent.ForkJoinPool(threads)
        executor = fjp
        (fjp, fjp)
      case "fix" =>
        val ftp = java.util.concurrent.Executors.newFixedThreadPool(threads)
        executor = ftp
        (ftp, ftp)
      case "fie" =>
        (scala.concurrent.InternalCallbackExecutor().asInstanceOf[Executor], scala.future.Future.InternalCallbackExecutor)
    }

    isCompleted = completed match {
      case "pre" => true
      case "post" => false
    }

    stdLibEC = new stdlib.ExecutionContext {
      private[this] val g = executorStdlib
      override final def execute(r: Runnable) = g.execute(r)
      override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
    }

    improvedEC = new stdlib.ExecutionContext {
      private[this] val g = executorImproved
      override final def execute(r: Runnable) = g.execute(r)
      override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
    }
  }

  @TearDown(Level.Trial)
  final def shutdown: Unit = {
    executor = executor match {
      case es: ExecutorService =>
        es.shutdown()
        es.awaitTermination(1, TimeUnit.MINUTES)
        null
      case _ => null
    }
  }

  @TearDown(Level.Invocation)
  def teardown: Unit

  @Setup(Level.Invocation)
  def setup: Unit

  def benchFunStdlib(ops: Int)(implicit ec: stdlib.ExecutionContext): stdlib.Future[Any]
  def benchFunImproved(ops: Int)(implicit ec: stdlib.ExecutionContext): improved.Future[Any]

  final def awaitStdlib(f: stdlib.Future[Any], timeout: Duration): Unit =
    stdlib.Await.ready(f, timeout)
    //while(!f.isCompleted) {}

  final def awaitImproved(f: improved.Future[Any], timeout: Duration): Unit =
    stdlib.Await.ready(f, timeout)
    //while(!f.isCompleted) {}

  @Benchmark
  @OperationsPerInvocation(1)
  final def x1_stdlib = awaitStdlib(benchFunStdlib(1)(stdLibEC), timeout)

  @Benchmark
  @OperationsPerInvocation(1)
  final def x1_improved = awaitImproved(benchFunImproved(1)(improvedEC), timeout)

  @Benchmark
  @OperationsPerInvocation(1024)
  final def x1024_stdlib = awaitStdlib(benchFunStdlib(1024)(stdLibEC), timeout)

  @Benchmark
  @OperationsPerInvocation(1024)
  final def x1024_improved = awaitImproved(benchFunImproved(1024)(improvedEC), timeout)
}

abstract class OpBenchmark extends AbstractBaseBenchmark {
  type Result = String

  private[this] final var stdlibP: stdlib.Promise[Result] = _
  private[this] final var improvedP: improved.Promise[Result] = _

  @Setup(Level.Invocation)
  override final def setup: Unit = {
    stdlibP = if (isCompleted) stdlib.Promise.successful("stdlib") else stdlib.Promise[Result]
    improvedP = if (isCompleted) improved.Promise.successful("improved") else improved.Promise[Result]
  }

  @TearDown(Level.Invocation)
  override final def teardown: Unit = {
    stdlibP = null
    improvedP = null
  }

  def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result]

  def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result]

  final override def benchFunStdlib(ops: Int)(implicit ec: stdlib.ExecutionContext): stdlib.Future[Any] = {
    @tailrec def next(i: Int)(f: stdlib.Future[Result]): stdlib.Future[Result] =
      if (i > 0) next(i - 1)(xformStdlib(f)) else f

    val p = stdlibP
    val f = p.future

    val cf = next(ops)(f)
    if (!isCompleted)
    p.success("stdlib")
    cf
  }

  final override def benchFunImproved(ops: Int)(implicit ec: stdlib.ExecutionContext): improved.Future[Any] = {
    @tailrec def next(i: Int)(f: improved.Future[Result]): improved.Future[Result] =
      if (i > 0) next(i - 1)(xformImproved(f)) else f

    val p = improvedP
    val f = p.future

    val cf = next(ops)(f)
    if(!isCompleted)
    p.success("improved")
    cf
  }
}

class MapBenchmark extends OpBenchmark {
  final val transformationFun = (r: Result) => r
  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    f.map(transformationFun)
  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    f.map(transformationFun)
}

class FilterBenchmark extends OpBenchmark {
  final val transformationFun = (r: Result) => true
  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    f.filter(transformationFun)
  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    f.filter(transformationFun)
}

class TransformBenchmark extends OpBenchmark {
  final val transformationFun = (t: Try[Result]) => t
  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    f.transform(transformationFun)
  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    f.transform(transformationFun)
}

class TransformWithBenchmark extends OpBenchmark {
  final val transformationFunStdlib = (t: Try[Result]) => stdlib.Future.fromTry(t)
  final val transformationFunImproved = (t: Try[Result]) => improved.Future.fromTry(t)
  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    f.transformWith(transformationFunStdlib)
  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    f.transformWith(transformationFunImproved)
}

class FlatMapBenchmark extends OpBenchmark {
  final val transformationFunStdlib = (t: Result) => stdlib.Future.successful(t)
  final val transformationFunImproved = (t: Result) => improved.Future.successful(t)
  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    f.flatMap(transformationFunStdlib)
  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    f.flatMap(transformationFunImproved)
}

class ZipWithBenchmark extends OpBenchmark {
  final val transformationFun = (t1: Result, t2: Result) => t2
  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    f.zipWith(f)(transformationFun)
  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    f.zipWith(f)(transformationFun)
}

class AndThenBenchmark extends OpBenchmark {
  final val effect: PartialFunction[Try[Result], Unit] = { case t: Try[Result] => () }
  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    f.andThen(effect)
  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    f.andThen(effect)
}

class VariousBenchmark extends OpBenchmark {
  final val mapFun: Result => Result = _.toUpperCase
  final val stdlibFlatMapFun: Result => stdlib.Future[Result] = r => stdlib.Future.successful(r)
  final val improvedFlatMapFun: Result => improved.Future[Result] = r => improved.Future.successful(r)
  final val filterFun: Result => Boolean = _ ne null
  final val transformFun: Try[Result] => Try[Result] = _ => throw null
  final val recoverFun: PartialFunction[Throwable, Result] = { case _ => "OK" }
  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    f.map(mapFun).flatMap(stdlibFlatMapFun).filter(filterFun).zipWith(f)((a, b) => a).transform(transformFun).recover(recoverFun)
  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    f.map(mapFun).flatMap(improvedFlatMapFun).filter(filterFun).zipWith(f)((a, b) => a).transform(transformFun).recover(recoverFun)
}

class LoopBenchmark extends OpBenchmark {
  val depth = 10
  val size  = 1000

  def pre_stdlib_loop(i: Int)(implicit ec: stdlib.ExecutionContext): stdlib.Future[Int] =
    if (i % depth == 0) stdlib.Future.successful(i + 1).flatMap(pre_stdlib_loop)
    else if (i < size) pre_stdlib_loop(i + 1).flatMap(stdlib.Future.successful)
    else stdlib.Future.successful(i)

  def pre_improved_loop(i: Int)(implicit ec: stdlib.ExecutionContext): improved.Future[Int] =
    if (i % depth == 0) improved.Future.successful(i + 1).flatMap(pre_improved_loop)
    else if (i < size) pre_improved_loop(i + 1).flatMap(improved.Future.successful)
    else improved.Future.successful(i)

  def post_stdlib_loop(i: Int)(implicit ec: stdlib.ExecutionContext): stdlib.Future[Int] =
    if (i % depth == 0) stdlib.Future(i + 1).flatMap(post_stdlib_loop)
    else if (i < size) post_stdlib_loop(i + 1).flatMap(i => stdlib.Future(i))
    else stdlib.Future(i)

  def post_improved_loop(i: Int)(implicit ec: stdlib.ExecutionContext): improved.Future[Int] =
    if (i % depth == 0) improved.Future(i + 1).flatMap(post_improved_loop)
    else if (i < size) post_improved_loop(i + 1).flatMap(i => improved.Future(i))
    else improved.Future(i)

  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    if (isCompleted) f.flatMap(s => pre_stdlib_loop(100).map(_ => s)) else f.flatMap(s => pre_stdlib_loop(100).map(_ => s))

  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    if (isCompleted) f.flatMap(s => pre_improved_loop(100).map(_ => s)) else f.flatMap(s => pre_improved_loop(100).map(_ => s))
}

class NoopBenchmark extends OpBenchmark {
  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    f

  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    f
}

/*class ZipBenchmark extends OpBenchmark {
  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    f.zip(f)
  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    f.zip(f)
}*/