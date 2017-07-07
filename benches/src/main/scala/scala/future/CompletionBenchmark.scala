package scala.future

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._
import scala.concurrent.Await
import scala.util.Try

import scala.{concurrent => stdlib}
import scala.{future => improved}

abstract class TryCompleteBenchFun { 
  def setup(): Unit
  def apply(ops: Int): Int
  def teardown(): Unit
}

final class StdlibTryCompleteBenchFun(val result: Try[Unit]) extends TryCompleteBenchFun {
  var p: stdlib.Promise[Unit] = _
  final def setup(): Unit = {
    p = stdlib.Promise[Unit]
  }
  final def apply(ops: Int): Int = {
    var i = ops
    while(i > 0) {
      p.tryComplete(result)
      i -= 1
    }
    i
  }
  final def teardown(): Unit = {
    p = null
  }
}

final class ImprovedTryCompleteBenchFun(val result: Try[Unit]) extends TryCompleteBenchFun {
  var p: improved.Promise[Unit] = _
  final def setup(): Unit = {
    p = improved.Promise[Unit]
  }
  final def apply(ops: Int): Int = {
    var i = ops
    while(i > 0) {
      p.tryComplete(result)
      i -= 1
    }
    i
  }
  final def teardown(): Unit = {
    p = null
  }
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput, Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1000)
@Measurement(iterations = 10000)
@Fork(value = 1, jvmArgsAppend = Array("-ea","-server","-XX:+UseCompressedOops","-XX:+AggressiveOpts","-XX:+AlwaysPreTouch", "-XX:+UseCondCardMark"))
class CompletionBenchmark {

  @Param(Array[String]("stdlib", "improved"))
  var impl: String = _

  @Param(Array[String]("success", "failure"))
  var result: String = _

  var benchFun: TryCompleteBenchFun = _

  @Setup(Level.Trial)
  final def startup = {
    val r = result match {
      case "success" => scala.util.Success(())
      case "failure" => scala.util.Failure(new RuntimeException("expected failure"))
      case other => throw new IllegalArgumentException("result must be either 'success' or 'failure' but was '" + other + "'")
    }

    benchFun = impl match {
      case "stdlib" => new StdlibTryCompleteBenchFun(r)
      case "improved" => new ImprovedTryCompleteBenchFun(r)
      case other => throw new IllegalArgumentException("impl must be either 'stdlib' or 'improved' but was '" + other + "'")
    }
  }

  @TearDown(Level.Invocation)
  final def teardown = benchFun.teardown()

  @Setup(Level.Invocation)
  final def setup = benchFun.setup()

  @Benchmark
  @OperationsPerInvocation(1)
  final def tryComplete_1 = benchFun(1)

  @Benchmark
  @OperationsPerInvocation(2)
  final def tryComplete_2 = benchFun(2)

  @Benchmark
  @OperationsPerInvocation(4)
  final def tryComplete_4 = benchFun(3)

  @Benchmark
  @OperationsPerInvocation(16)
  final def tryComplete_16 = benchFun(16)

  @Benchmark
  @OperationsPerInvocation(64)
  final def tryComplete_64 = benchFun(64)

  @Benchmark
  @OperationsPerInvocation(1024)
  final def tryComplete_1024 = benchFun(1024)

  @Benchmark
  @OperationsPerInvocation(8192)
  final def tryComplete_8192 = benchFun(8192)
}
