package scala.future

import scala.concurrent.duration._
import java.util.concurrent.{TimeUnit,Executor, ExecutorService}
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.annotations._
import scala.util.{ Try, Success }
import scala.annotation.tailrec
import scala.{concurrent => stdlib}
import scala.{future => improved}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1000)
@Measurement(iterations = 10000)
@Fork(value = 1, jvmArgsAppend = Array("-ea","-server","-XX:+UseCompressedOops","-XX:+AggressiveOpts","-XX:+AlwaysPreTouch", "-XX:+UseCondCardMark"))
@Threads(value = 1)
abstract class AbstractBaseBenchmark {
  @Param(Array[String]("fjp", "fix", "fie"))
  final var pool: String = _

  @Param(Array[String]("1"))
  final var threads: Int = _

  @Param(Array[String]("1024"))
  final var recursion: Int = _

  final var executor: Executor = _

  final var stdlibEC: stdlib.ExecutionContext = _
  final var improvedEC: stdlib.ExecutionContext = _

  final val timeout = 60.seconds

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

    stdlibEC = new stdlib.ExecutionContext {
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
        try es.shutdown() finally es.awaitTermination(1, TimeUnit.MINUTES)
        null
      case _ => null
    }
  }
}

abstract class OpBenchmark extends AbstractBaseBenchmark {
  type Result = String

  final val stdlibSuccess = Success("stdlib")
  final val improvedSuccess = Success("improved")

  final val improved_pre_p: improved.Promise[Result] = improved.Promise.fromTry(improvedSuccess)
  final val stdlib_pre_p: stdlib.Promise[Result] = stdlib.Promise.fromTry(stdlibSuccess)

  def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result]
  def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result]

  private[this] final def await(a: stdlib.Awaitable[Result]): Result =
    stdlib.Await.result(a, timeout)

  @Benchmark final def stdlib_pre(): Result = {
    @tailrec def next(i: Int)(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) next(i - 1)(xformStdlib(f)) else f
    await(next(recursion)(stdlib_pre_p.future)(stdlibEC))
  }

  @Benchmark final def stdlib_post(): Result = {
    @tailrec def next(i: Int)(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) next(i - 1)(xformStdlib(f)) else f

    val stdlib_post_p = stdlib.Promise[Result]()
    val f = next(recursion)(stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(): Result = {
    @tailrec def next(i: Int)(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) next(i - 1)(xformImproved(f)) else f
    await(next(recursion)(improved_pre_p.future)(improvedEC))
  }

  @Benchmark final def improved_post(): Result = {
    @tailrec def next(i: Int)(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) next(i - 1)(xformImproved(f)) else f

    val improved_post_p = improved.Promise[Result]()
    val f = next(recursion)(improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class NoopBenchmark extends OpBenchmark {
  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    f
  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    f
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
    if (f eq stdlib_pre_p.future) f.flatMap(s => pre_stdlib_loop(100).map(_ => s)) else f.flatMap(s => post_stdlib_loop(100).map(_ => s))

  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    if (f eq improved_pre_p.future) f.flatMap(s => pre_improved_loop(100).map(_ => s)) else f.flatMap(s => post_improved_loop(100).map(_ => s))
}

/*class ZipBenchmark extends OpBenchmark {
  override final def xformStdlib(f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
    f.zip(f)
  override final def xformImproved(f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
    f.zip(f)
}*/