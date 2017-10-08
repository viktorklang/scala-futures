package scala.future

import scala.concurrent.duration._
import java.util.concurrent.{TimeUnit,Executor, ExecutorService}
import org.openjdk.jmh.annotations._
import scala.util.Try

import scala.{concurrent => stdlib}
import scala.{future => improved}

abstract class TransformationBenchFun {
  implicit def ec: stdlib.ExecutionContext
  type Result = String
  val transformation = (s: Result) => s
  def setup(): Unit
  def apply(ops: Int): stdlib.Awaitable[Result]
  def teardown(): Unit
}

final class StdlibTransformationBenchFun(implicit val ec: stdlib.ExecutionContext) extends TransformationBenchFun {
  var p: stdlib.Promise[Result] = _
  final def setup(): Unit = {
    p = stdlib.Promise[Result]
  }
  final def apply(ops: Int): stdlib.Future[Result] = {
    var cf = p.future
    var i  = ops
    while(i > 0) {
      cf = cf.map(transformation)
      i -= 1
    }
    p.success("stlib")
    cf
  }
  final def teardown(): Unit = {
    p = null
  }
}

final class ImprovedTransformationBenchFun(implicit val ec: stdlib.ExecutionContext) extends TransformationBenchFun {
  var p: improved.Promise[Result] = _
  final def setup(): Unit = {
    p = improved.Promise[Result]
  }
  final def apply(ops: Int): improved.Future[Result] = {
    var cf = p.future
    var i  = ops
    while(i > 0) {
      cf = cf.map(transformation)
      i -= 1
    }
    p.success("improved")
    cf
  }
  final def teardown(): Unit = {
    p = null
  }
}


@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1000)
@Measurement(iterations = 10000)
@Fork(value = 1, jvmArgsAppend = Array("-ea","-server","-XX:+UseCompressedOops","-XX:+AggressiveOpts","-XX:+AlwaysPreTouch", "-XX:+UseCondCardMark"))
class TransformationBenchmark {

  @Param(Array[String]("stdlib", "improved"))
  var impl: String = _

  @Param(Array[String]("fjp", "fix"))
  var pool: String = _

  @Param(Array[String]("1"))
  var threads: Int = _

  var benchFun: TransformationBenchFun = _

  var executor: Executor = _

  val timeout = 60.seconds

  @Setup(Level.Trial)
  final def startup: Unit = {
    executor = pool match {
      case "fjp" => new java.util.concurrent.ForkJoinPool(threads)
      case "fix" => java.util.concurrent.Executors.newFixedThreadPool(threads)
    }

    benchFun = impl match {
      case "stdlib" => new StdlibTransformationBenchFun()(new stdlib.ExecutionContext {
        val g = executor
        override final def execute(r: Runnable) = g.execute(r)
        override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
      })
      case "improved" => new ImprovedTransformationBenchFun()(new BatchingExecutor with stdlib.ExecutionContext {
        val g = executor
        override final def unbatchedExecute(r: Runnable) = g.execute(r)
        override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
      })
      case other => throw new IllegalArgumentException(s"impl was '$other'")
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
  final def teardown = benchFun.teardown()

  @Setup(Level.Invocation)
  final def setup = benchFun.setup()

  @Benchmark
  @OperationsPerInvocation(1)
  final def transformation_1 = stdlib.Await.result(benchFun(1), timeout)

  @Benchmark
  @OperationsPerInvocation(2)
  final def transformation_2 = stdlib.Await.result(benchFun(2), timeout)

  @Benchmark
  @OperationsPerInvocation(4)
  final def transformation_4 = stdlib.Await.result(benchFun(4), timeout)

  @Benchmark
  @OperationsPerInvocation(16)
  final def transformation_16 = stdlib.Await.result(benchFun(16), timeout)

  @Benchmark
  @OperationsPerInvocation(64)
  final def transformation_64 = stdlib.Await.result(benchFun(64), timeout)

  @Benchmark
  @OperationsPerInvocation(1024)
  final def transformation_1024 = stdlib.Await.result(benchFun(1024), timeout)

  @Benchmark
  @OperationsPerInvocation(8192)
  final def transformation_8192 = stdlib.Await.result(benchFun(8192), timeout)
}
