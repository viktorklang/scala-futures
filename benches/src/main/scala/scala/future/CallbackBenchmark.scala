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
class CallbackBenchmark {

  val callback = (_: Try[Unit]) => ()

  var stdlibPromise: stdlib.Promise[Unit] = _

  var improvedPromise: improved.Promise[Unit] = _

  @TearDown(Level.Invocation)
  final def teardown = {
    stdlibPromise = null
    improvedPromise = null
  }

  @Setup(Level.Invocation)
  final def setup = {
    stdlibPromise = stdlib.Promise[Unit]
    improvedPromise = improved.Promise[Unit]
  }

  @Benchmark
  @OperationsPerInvocation(1)
  final def addingCallbacksImproved_1 = addingCallbacksImproved(1)

  @Benchmark
  @OperationsPerInvocation(2)
  final def addingCallbacksImproved_2 = addingCallbacksImproved(2)

  @Benchmark
  @OperationsPerInvocation(4)
  final def addingCallbacksImproved_4 = addingCallbacksImproved(4)

  @Benchmark
  @OperationsPerInvocation(16)
  final def addingCallbacksImproved_16 = addingCallbacksImproved(16)

  @Benchmark
  @OperationsPerInvocation(64)
  final def addingCallbacksImproved_64 = addingCallbacksImproved(64)

  @Benchmark
  @OperationsPerInvocation(8192)
  final def addingCallbacksImproved_8192 = addingCallbacksImproved(8192)

  @Benchmark
  @OperationsPerInvocation(1)
  final def addingCallbacksStdlib_1 = addingCallbacksStdlib(1)

  @Benchmark
  @OperationsPerInvocation(2)
  final def addingCallbacksStdlib_2 = addingCallbacksStdlib(2)

  @Benchmark
  @OperationsPerInvocation(4)
  final def addingCallbacksStdlib_4 = addingCallbacksStdlib(4)

  @Benchmark
  @OperationsPerInvocation(16)
  final def addingCallbacksStdlib_16 = addingCallbacksStdlib(16)

  @Benchmark
  @OperationsPerInvocation(64)
  final def addingCallbacksStdlib_64 = addingCallbacksStdlib(64)

  @Benchmark
  @OperationsPerInvocation(8192)
  final def addingCallbacksStdlib_8192 = addingCallbacksStdlib(8192)

  final def addingCallbacksImproved(ops: Int) = {
    import stdlib.ExecutionContext.Implicits._
    val f = improvedPromise.future
    var i = ops
    while(i > 0) {
      f.onComplete(callback)
      i -= 1
    }
  }
  
  final def addingCallbacksStdlib(ops: Int) = {
    import stdlib.ExecutionContext.Implicits._
    val f = stdlibPromise.future
    var i = ops
    while(i > 0) {
      f.onComplete(callback)
      i -= 1
    }
  }
}
