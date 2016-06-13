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
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class CallbackBenchmark {

  val callback = (_: Try[Unit]) => ()

  var stdlibPromise: stdlib.Promise[Unit] = _

  var improvedPromise: improved.Promise[Unit] = _

  @TearDown(Level.Invocation)
  def teardown = {
    stdlibPromise = null
    improvedPromise = null
  }

  @Setup(Level.Invocation)
  @Group("stdlib")
  def setupStdlib = stdlibPromise = stdlib.Promise[Unit]

  @Setup(Level.Invocation)
  @Group("improved")
  def setupImproved = improvedPromise = improved.Promise[Unit]

  @Benchmark
  @Group("improved")
  @OperationsPerInvocation(1)
  def addingCallbacksImproved_1 = addingCallbacksImproved(1)

  @Benchmark
  @Group("improved")
  @OperationsPerInvocation(2)
  def addingCallbacksImproved_2 = addingCallbacksImproved(2)

  @Benchmark
  @Group("improved")
  @OperationsPerInvocation(4)
  def addingCallbacksImproved_4 = addingCallbacksImproved(4)

  @Benchmark
  @Group("improved")
  @OperationsPerInvocation(16)
  def addingCallbacksImproved_16 = addingCallbacksImproved(16)

  @Benchmark
  @Group("improved")
  @OperationsPerInvocation(64)
  def addingCallbacksImproved_64 = addingCallbacksImproved(64)

  @Benchmark
  @Group("improved")
  @OperationsPerInvocation(8192)
  def addingCallbacksImproved_8192 = addingCallbacksImproved(8192)

  @Benchmark
  @Group("stdlib")
  @OperationsPerInvocation(1)
  def addingCallbacksStdlib_1 = addingCallbacksStdlib(1)

  @Benchmark
  @Group("stdlib")
  @OperationsPerInvocation(2)
  def addingCallbacksStdlib_2 = addingCallbacksStdlib(2)

  @Benchmark
  @Group("stdlib")
  @OperationsPerInvocation(4)
  def addingCallbacksStdlib_4 = addingCallbacksStdlib(4)

  @Benchmark
  @Group("stdlib")
  @OperationsPerInvocation(16)
  def addingCallbacksStdlib_16 = addingCallbacksStdlib(16)

  @Benchmark
  @Group("stdlib")
  @OperationsPerInvocation(64)
  def addingCallbacksStdlib_64 = addingCallbacksStdlib(64)

  @Benchmark
  @Group("stdlib")
  @OperationsPerInvocation(8192)
  def addingCallbacksStdlib_8192 = addingCallbacksStdlib(8192)

  def addingCallbacksImproved(ops: Int) = {
    import stdlib.ExecutionContext.Implicits._
    val f = improvedPromise.future
    var i = ops
    while(i > 0) {
      f.onComplete(callback)
      i -= 1
    }
  }
  
  def addingCallbacksStdlib(ops: Int) = {
    import stdlib.ExecutionContext.Implicits._
    val f = stdlibPromise.future
    var i = ops
    while(i > 0) {
      f.onComplete(callback)
      i -= 1
    }
  }
}
