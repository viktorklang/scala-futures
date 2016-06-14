package scala.future

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._
import scala.concurrent.Await
import scala.util.Try

import scala.{concurrent => stdlib}
import scala.{future => improved}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 10)
@Measurement(iterations = 10000)
@Fork(1)
class CompletionBenchmark {

  var stdlibPromise: stdlib.Promise[Unit] = _

  var improvedPromise: improved.Promise[Unit] = _

  @Param(Array[String]("true", "false"))
  var failure: String = _

  var result: Try[Unit] = _

  @TearDown(Level.Invocation)
  final def teardown = {
    stdlibPromise = null
    improvedPromise = null
  }

  @Setup(Level.Iteration)
  final def startup = {
    result = if (java.lang.Boolean.valueOf(failure)) Try(throw new RuntimeException("failedResult")) else Try(())
  }

  @Setup(Level.Invocation)
  final def setup = {
    stdlibPromise = stdlib.Promise[Unit]
    improvedPromise = improved.Promise[Unit]
  }

  @Benchmark
  @OperationsPerInvocation(1)
  final def tryCompleteImproved_1 = tryCompleteImproved(1)

  @Benchmark
  @OperationsPerInvocation(2)
  final def tryCompleteImproved_2 = tryCompleteImproved(2)

  @Benchmark
  @OperationsPerInvocation(4)
  final def tryCompleteImproved_4 = tryCompleteImproved(4)

  @Benchmark
  @OperationsPerInvocation(16)
  final def tryCompleteImproved_16 = tryCompleteImproved(16)

  @Benchmark
  @OperationsPerInvocation(64)
  final def tryCompleteImproved_64 = tryCompleteImproved(64)

  @Benchmark
  @OperationsPerInvocation(8192)
  final def tryCompleteImproved_8192 = tryCompleteImproved(8192)

  @Benchmark
  @OperationsPerInvocation(1)
  final def tryCompleteStdlib_1 = tryCompleteStdlib(1)

  @Benchmark
  @OperationsPerInvocation(2)
  final def tryCompleteStdlib_2 = tryCompleteStdlib(2)

  @Benchmark
  @OperationsPerInvocation(4)
  final def tryCompleteStdlib_4 = tryCompleteStdlib(4)

  @Benchmark
  @OperationsPerInvocation(16)
  final def tryCompleteStdlib_16 = tryCompleteStdlib(16)

  @Benchmark
  @OperationsPerInvocation(64)
  final def tryCompleteStdlib_64 = tryCompleteStdlib(64)

  @Benchmark
  @OperationsPerInvocation(8192)
  final def tryCompleteStdlib_8192 = tryCompleteStdlib(8192)

  final def tryCompleteImproved(ops: Int) = {
    var i = ops
    while(i > 0) {
      improvedPromise.tryComplete(result)
      i -= 1
    }
    i
  }
  
  final def tryCompleteStdlib(ops: Int) = {
    var i = ops
    while(i > 0) {
      stdlibPromise.tryComplete(result)
      i -= 1
    }
    i
  }
}
