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

final class StdlibTransformationBenchFun(val e: Executor) extends TransformationBenchFun {
  implicit final val ec: stdlib.ExecutionContext = new stdlib.ExecutionContext {
    override final def execute(r: Runnable) = e.execute(r)
    override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
  }
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
    p.future
  }
  final def teardown(): Unit = {
    p = null
  }
}

final class ImprovedTransformationBenchFun(val e: Executor) extends TransformationBenchFun {
  implicit final val ec: stdlib.ExecutionContext = new stdlib.ExecutionContext with BatchingExecutor {
    override final def unbatchedExecute(r: Runnable) = e.execute(r)
    override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
  }
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
    p.future
  }
  final def teardown(): Unit = {
    p = null
  }
}


@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1000)
@Measurement(iterations = 10000)
@Fork(1)
class TransformationBenchmark {

  @Param(Array[String]("stdlib", "improved"))
  var impl: String = _

  var benchFun: TransformationBenchFun = _

  val timeout = 60.seconds

  @Setup(Level.Trial)
  final def startup = {
    benchFun = impl match {
      case "stdlib" => new StdlibTransformationBenchFun(stdlib.ExecutionContext.global)
      case "improved" => new ImprovedTransformationBenchFun(stdlib.ExecutionContext.global)
      case other => throw new IllegalArgumentException("impl must be either 'stdlib' or 'improved' but was '" + other + "'")
    }
  }

  @TearDown(Level.Trial)
  final def shutdown: Unit = ()

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
  final def transformation_4 = stdlib.Await.result(benchFun(3), timeout)

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
