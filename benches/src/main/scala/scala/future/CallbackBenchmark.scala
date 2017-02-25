package scala.future

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._
import scala.util.Try

import scala.{concurrent => stdlib}
import scala.{future => improved}

abstract class CallbackBenchFun {
  implicit def ec: stdlib.ExecutionContext
  val callback = (_: Try[Unit]) => ()

  def setup(): Unit
  def apply(ops: Int): Int
  def teardown(): Unit
}

class StdlibCallbackBenchFun extends CallbackBenchFun {
  implicit final val ec: stdlib.ExecutionContext = new stdlib.ExecutionContext {
    val g = stdlib.ExecutionContext.global
    override final def execute(r: Runnable) = g.execute(r)
    override final def reportFailure(t: Throwable) = g.reportFailure(t)
  }
  var p: stdlib.Promise[Unit] = _
  final def setup(): Unit = {
    p = stdlib.Promise[Unit]
  }
  final def apply(ops: Int): Int = {
    val f = p.future
    var i = ops
    while(i > 0) {
      f.onComplete(callback)
      i -= 1
    }
    i
  }
  final def teardown(): Unit = {
    p = null
  }
}

class ImprovedCallbackBenchFun extends CallbackBenchFun {
  implicit final val ec: stdlib.ExecutionContext = new stdlib.ExecutionContext with BatchingExecutor {
    val g = stdlib.ExecutionContext.global
    override final def unbatchedExecute(r: Runnable) = g.execute(r)
    override final def reportFailure(t: Throwable) = g.reportFailure(t)
  }
  var p: improved.Promise[Unit] = _
  final def setup(): Unit = {
    p = improved.Promise[Unit]
  }
  final def apply(ops: Int): Int = {
    val f = p.future
    var i = ops
    while(i > 0) {
      f.onComplete(callback)
      i -= 1
    }
    i
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
class CallbackBenchmark {

  @Param(Array[String]("stdlib", "improved"))
  var impl: String = _

  var benchFun: CallbackBenchFun = _

  @Setup(Level.Trial)
  final def startup = {
    benchFun = impl match {
      case "stdlib" => new StdlibCallbackBenchFun
      case "improved" => new ImprovedCallbackBenchFun
      case other => throw new IllegalArgumentException("impl must be either 'stdlib' or 'improved' but was '" + other + "'")
    }
  }

  @TearDown(Level.Invocation)
  final def teardown = benchFun.teardown()

  @Setup(Level.Invocation)
  final def setup = benchFun.setup()

  @Benchmark
  @OperationsPerInvocation(1)
  final def onComplete_1 = benchFun(1)

  @Benchmark
  @OperationsPerInvocation(2)
  final def onComplete_2 = benchFun(2)

  @Benchmark
  @OperationsPerInvocation(4)
  final def onComplete_4 = benchFun(3)

  @Benchmark
  @OperationsPerInvocation(16)
  final def onComplete_16 = benchFun(16)

  @Benchmark
  @OperationsPerInvocation(64)
  final def onComplete_64 = benchFun(64)

  @Benchmark
  @OperationsPerInvocation(1024)
  final def onComplete_1024 = benchFun(1024)

  @Benchmark
  @OperationsPerInvocation(8192)
  final def onComplete_8192 = benchFun(8192)
}
