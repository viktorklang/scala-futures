package scala.future

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._
import scala.concurrent.Await

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Fork(5)
@Warmup(iterations = 1000)
@Measurement(iterations = 4000)
class CallbackBenchmark {

  @TearDown(Level.Trial)
  def shutdown(): Unit = ()

  @Benchmark
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  def addingCallbacks = ()
}

