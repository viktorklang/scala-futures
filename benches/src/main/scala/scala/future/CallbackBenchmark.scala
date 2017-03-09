package scala.future

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._
import scala.util.Try
import java.util.concurrent.{TimeUnit,Executor, ExecutorService}
import scala.{concurrent => stdlib}
import scala.{future => improved}

abstract class CallbackBenchFun {
  implicit def ec: stdlib.ExecutionContext
  val callback = (_: Try[Unit]) => ()

  def setup(): Unit
  def apply(ops: Int): Int
  def teardown(): Unit
}

final class StdlibCallbackBenchFun(implicit final val ec: stdlib.ExecutionContext) extends CallbackBenchFun {
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

final class ImprovedCallbackBenchFun(implicit final val ec: stdlib.ExecutionContext) extends CallbackBenchFun {
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
@Fork(value = 1, jvmArgsAppend = Array("-ea","-server","-XX:+UseCompressedOops","-XX:+AggressiveOpts","-XX:+AlwaysPreTouch", "-XX:+UseCondCardMark"))
class CallbackBenchmark {

  @Param(Array[String]("stdlib", "improved", "improved2"))
  var impl: String = _

  @Param(Array[String]("fjp(1)", "fjp(cores)", "fix(1)", "fix(cores)"))
  var pool: String = _

  var executor: Executor = _

  var benchFun: CallbackBenchFun = _

  @Setup(Level.Trial)
  final def startup = {

    val cores = java.lang.Runtime.getRuntime.availableProcessors
    executor = pool match {
      case "fjp(1)"     => new java.util.concurrent.ForkJoinPool(1)
      case "fjp(cores)" => new java.util.concurrent.ForkJoinPool(cores)
      case "fix(1)"     => java.util.concurrent.Executors.newFixedThreadPool(1)
      case "fix(cores)" => java.util.concurrent.Executors.newFixedThreadPool(cores)
    }

    benchFun = impl match {
      case "stdlib" => new StdlibCallbackBenchFun()(new stdlib.ExecutionContext {
        val g = executor
        override final def execute(r: Runnable) = g.execute(r)
        override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
      })
      case "improved" => new ImprovedCallbackBenchFun()(new stdlib.ExecutionContext {
        val g = executor
        override final def execute(r: Runnable) = g.execute(r)
        override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
      })
      case "improved2" => new ImprovedCallbackBenchFun()(new BatchingExecutor with stdlib.ExecutionContext {
        val g = executor
        override final def unbatchedExecute(r: Runnable) = g.execute(r)
        override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
      })
      case other => throw new IllegalArgumentException("impl must be either 'stdlib', 'improved', or 'improved2' but was '" + other + "'")
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
