package scala.future

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._
import scala.concurrent.Await
import scala.util.Try

import scala.{concurrent => stdlib}
import scala.{future => improved}

abstract class TryCompleteWithBenchFun { 
  def setup(): Unit
  def apply(ops: Int): stdlib.Awaitable[Unit]
  def teardown(): Unit
}

final class StdlibTryCompleteWithBenchFun(final val v: Try[Unit]) extends TryCompleteWithBenchFun {
  var p: stdlib.Promise[Unit] = _
  var result: stdlib.Promise[Unit] = _
  override final def setup(): Unit = {
    p = stdlib.Promise[Unit]
    result = stdlib.Promise[Unit]
  }
  override final def apply(ops: Int): stdlib.Awaitable[Unit] = {
    var i = ops
    while(i > 0) {
      p.tryCompleteWith(result.future)
      i -= 1
    }
    result.complete(v)
    p.future
  }

  override final def teardown(): Unit = {
    p = null
    result = null
  }
}

final class ImprovedTryCompleteWithBenchFun(final val v: Try[Unit]) extends TryCompleteWithBenchFun {
  var p: improved.Promise[Unit] = _
  var result: improved.Promise[Unit] = _
  final def setup(): Unit = {
    p = improved.Promise[Unit]
    result = improved.Promise[Unit]
  }
  final def apply(ops: Int): stdlib.Awaitable[Unit] = {
    var i = ops
    while(i > 0) {
      p.tryCompleteWith(result.future)
      i -= 1
    }
    result.complete(v)
    p.future
  }
  final def teardown(): Unit = {
    p = null
    result = null
  }
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput, Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1000)
@Measurement(iterations = 10000)
@Fork(value = 1, jvmArgsAppend = Array("-ea","-server","-XX:+UseCompressedOops","-XX:+AggressiveOpts","-XX:+AlwaysPreTouch", "-XX:+UseCondCardMark"))
class CompletionWithBenchmark {

  @Param(Array[String]("stdlib", "improved"))
  var impl: String = _

  @Param(Array[String]("success", "failure"))
  var result: String = _

  var benchFun: TryCompleteWithBenchFun = _

  val timeout = 60.seconds

  @Setup(Level.Trial)
  final def startup = {
    val r = result match {
      case "success" => scala.util.Success(())
      case "failure" => scala.util.Failure(new RuntimeException("expected failure"))
      case other => throw new IllegalArgumentException("result must be either 'success' or 'failure' but was '" + other + "'")
    }

    benchFun = impl match {
      case "stdlib" => new StdlibTryCompleteWithBenchFun(r)
      case "improved" => new ImprovedTryCompleteWithBenchFun(r)
      case other => throw new IllegalArgumentException("impl must be either 'stdlib' or 'improved' but was '" + other + "'")
    }
  }

  @TearDown(Level.Invocation)
  final def teardown = benchFun.teardown()

  @Setup(Level.Invocation)
  final def setup = benchFun.setup()

  @Benchmark
  @OperationsPerInvocation(1)
  final def tryCompleteWith_1 = stdlib.Await.ready(benchFun(1), timeout)

  @Benchmark
  @OperationsPerInvocation(2)
  final def tryCompleteWith_2 = stdlib.Await.ready(benchFun(2), timeout)

  @Benchmark
  @OperationsPerInvocation(4)
  final def tryCompleteWith_4 = stdlib.Await.ready(benchFun(3), timeout)

  @Benchmark
  @OperationsPerInvocation(16)
  final def tryCompleteWith_16 = stdlib.Await.ready(benchFun(16), timeout)

  @Benchmark
  @OperationsPerInvocation(64)
  final def tryCompleteWith_64 = stdlib.Await.ready(benchFun(64), timeout)

  @Benchmark
  @OperationsPerInvocation(1024)
  final def tryCompleteWith_1024 = stdlib.Await.ready(benchFun(1024), timeout)

  @Benchmark
  @OperationsPerInvocation(8192)
  final def tryCompleteWith_8192 = stdlib.Await.ready(benchFun(8192), timeout)
}
