package scala.future

import scala.concurrent.duration._
import java.util.concurrent.{ TimeUnit, Executor, Executors, ExecutorService, ForkJoinPool, CountDownLatch }
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.annotations._
import scala.util.{ Try, Success, Failure }
import scala.annotation.tailrec
import scala.{concurrent => stdlib}
import scala.{future => improved}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1000)
@Measurement(iterations = 10000)
@Fork(value = 1, jvmArgsAppend = Array(/*"-agentpath:/Applications/YourKit-Java-Profiler-2017.02.app/Contents/Resources/bin/mac/libyjpagent.jnilib", */ "-Xmx512M", "-Xms512M", "-ea", "-server", "-XX:+UseCompressedOops", "-XX:+AlwaysPreTouch", "-XX:+UseCondCardMark"))
@Threads(value = 1)
abstract class AbstractBaseBenchmark {
  @Param(Array[String]("fjp", "fix", "fie"))
  final var pool: String = _

  @Param(Array[String]("1"))
  final var threads: Int = _

  @Param(Array[String]("1024"))
  final var recursion: Int = _

  //@Param(Array[String]("success", "failure"))
  //final var status: String = _

  final var executor: Executor = _

  final var stdlibEC: stdlib.ExecutionContext = _
  final var improvedEC: stdlib.ExecutionContext = _

  final val timeout = 60.seconds

  @Setup(Level.Trial)
  def startup: Unit = {
    val (executorStdlib, executorImproved) = pool match {
      case "fjp" =>
        val fjp = new ForkJoinPool(threads)
        executor = fjp
        (fjp, fjp)
      case "fix" =>
        val ftp = Executors.newFixedThreadPool(threads)
        executor = ftp
        (ftp, ftp)
      case "gbl" =>
        (stdlib.ExecutionContext.global, stdlib.ExecutionContext.global)
      case "fie" =>
        (scala.concurrent.InternalCallbackExecutor().asInstanceOf[Executor], scala.future.Future.InternalCallbackExecutor)
    }

    stdlibEC =
      if (executorStdlib.isInstanceOf[stdlib.ExecutionContext]) executorStdlib.asInstanceOf[stdlib.ExecutionContext]
      else if (true) {
        new stdlib.ExecutionContext with stdlib.BatchingEC {
          private[this] final val g = executorStdlib
          override final def unbatchedExecute(r: Runnable) = g.execute(r)
          override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
        }
      } else {
        new stdlib.ExecutionContext {
          private[this] final val g = executorStdlib
          override final def execute(r: Runnable) = g.execute(r)
          override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
        }
      }

    improvedEC =
      if (executorImproved.isInstanceOf[stdlib.ExecutionContext]) executorImproved.asInstanceOf[stdlib.ExecutionContext]
      else if (true) {
        new stdlib.ExecutionContext with improved.BatchingExecutor {
          private[this] final val g = executorImproved
          override final def unbatchedExecute(r: Runnable) = g.execute(r)
          override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
        }
      } else {
        new stdlib.ExecutionContext {
          private[this] final val g = executorImproved
          override final def execute(r: Runnable) = g.execute(r)
          override final def reportFailure(t: Throwable) = t.printStackTrace(System.err)
        }
      }
  }

  @TearDown(Level.Trial)
  final def shutdown: Unit = {
    executor = executor match {
      case es: ExecutorService =>
        try es.shutdown() finally es.awaitTermination(1, TimeUnit.MINUTES)
        null
      case _ => null
    }
  }
}

abstract class OpBenchmark extends AbstractBaseBenchmark {
  type Result = String

  final val stdlibFailure = Failure(new Exception("stdlib"))
  final val improvedFailure = Failure(new Exception("improved"))

  final val stdlibSuccess = Success("stdlib")
  final val improvedSuccess = Success("improved")

  final val improved_pre_s_p: improved.Promise[Result] = improved.Promise.fromTry(improvedSuccess)
  final val stdlib_pre_s_p: stdlib.Promise[Result] = stdlib.Promise.fromTry(stdlibSuccess)

  final val improved_pre_f_p: improved.Promise[Result] = improved.Promise.fromTry(improvedFailure)
  final val stdlib_pre_f_p: stdlib.Promise[Result] = stdlib.Promise.fromTry(stdlibFailure)

  final var stdlibResult: Try[Result] = _
  final var improvedResult: Try[Result] = _

  override def startup: Unit = {
    super.startup
    /*stdlibResult = status match {
      case "success" => stdlibSuccess
      case "failure" => stdlibFailure
    }

    improvedResult = status match {
      case "success" => improvedSuccess
      case "failure" => improvedFailure
    }*/
  }

  protected final def await[T](a: stdlib.Future[T]): Boolean = {
    var r: Option[Try[T]] = None
    do {
      r = a.value
    } while(r eq None);
    r.get.isInstanceOf[Success[T]]
  }

  protected final def await[T](a: improved.Future[T]): Boolean = {
    var r: Option[Try[T]] = None
    do {
      r = a.value
    } while(r eq None);
    r.get.isInstanceOf[Success[T]]
  }
}

class NoopBenchmark extends OpBenchmark {
  @tailrec private[this] final def nextS(i: Int, bh: Blackhole,f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, bh, f) } else {  bh.consume(f); f }

  @tailrec private[this] final def nextI(i: Int, bh: Blackhole,f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, bh, f) } else { bh.consume(f); f }

  @Benchmark final def stdlib_pre(bh: Blackhole): Boolean =
    await(nextS(recursion, bh, stdlib_pre_s_p.future)(stdlibEC))

  @Benchmark final def stdlib_post(bh: Blackhole): Boolean = {
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = nextS(recursion, bh, stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(bh: Blackhole): Boolean = {
    await(nextI(recursion, bh, improved_pre_s_p.future)(improvedEC))
  }

  @Benchmark final def improved_post(bh: Blackhole): Boolean = {
    val improved_post_p = improved.Promise[Result]()
    val f = nextI(recursion, bh, improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class MapBenchmark extends OpBenchmark {
  final val transformationFun = (r: Result) => r

  @tailrec private[this] final def nextS(i: Int, f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, f.map(transformationFun)) } else { f }

  @tailrec private[this] final def nextI(i: Int, f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, f.map(transformationFun)) } else { f }

  @Benchmark final def stdlib_pre(): Boolean =
    await(nextS(recursion, stdlib_pre_s_p.future)(stdlibEC))

  @Benchmark final def stdlib_post(): Boolean = {
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = nextS(recursion, stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    await(nextI(recursion, improved_pre_s_p.future)(improvedEC))
  }

  @Benchmark final def improved_post(): Boolean = {
    val improved_post_p = improved.Promise[Result]()
    val f = nextI(recursion, improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class FilterBenchmark extends OpBenchmark {
  final val transformationFun = (r: Result) => true

  @tailrec private[this] final def nextS(i: Int, f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, f.filter(transformationFun)) } else { f }

  @tailrec private[this] final def nextI(i: Int, f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, f.filter(transformationFun)) } else { f }

  @Benchmark final def stdlib_pre(): Boolean =
    await(nextS(recursion, stdlib_pre_s_p.future)(stdlibEC))

  @Benchmark final def stdlib_post(): Boolean = {
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = nextS(recursion, stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    await(nextI(recursion, improved_pre_s_p.future)(improvedEC))
  }

  @Benchmark final def improved_post(): Boolean = {
    val improved_post_p = improved.Promise[Result]()
    val f = nextI(recursion, improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class TransformBenchmark extends OpBenchmark {
  final val transformationFun = (t: Try[Result]) => t

  @tailrec private[this] final def nextS(i: Int, f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, f.transform(transformationFun)) } else { f }

  @tailrec private[this] final def nextI(i: Int, f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, f.transform(transformationFun)) } else { f }

  @Benchmark final def stdlib_pre(): Boolean =
    await(nextS(recursion, stdlib_pre_s_p.future)(stdlibEC))

  @Benchmark final def stdlib_post(): Boolean = {
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = nextS(recursion, stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    await(nextI(recursion, improved_pre_s_p.future)(improvedEC))
  }

  @Benchmark final def improved_post(): Boolean = {
    val improved_post_p = improved.Promise[Result]()
    val f = nextI(recursion, improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class TransformWithBenchmark extends OpBenchmark {
  final val transformationFunStdlib = (t: Try[Result]) => stdlib.Future.fromTry(t)
  final val transformationFunImproved = (t: Try[Result]) => improved.Future.fromTry(t)

  @tailrec private[this] final def nextS(i: Int, f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, f.transformWith(transformationFunStdlib)) } else { f }

  @tailrec private[this] final def nextI(i: Int, f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, f.transformWith(transformationFunImproved)) } else { f }

  @Benchmark final def stdlib_pre(): Boolean =
    await(nextS(recursion, stdlib_pre_s_p.future)(stdlibEC))

  @Benchmark final def stdlib_post(): Boolean = {
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = nextS(recursion, stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    await(nextI(recursion, improved_pre_s_p.future)(improvedEC))
  }

  @Benchmark final def improved_post(): Boolean = {
    val improved_post_p = improved.Promise[Result]()
    val f = nextI(recursion, improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class FlatMapBenchmark extends OpBenchmark {
  final val transformationFunStdlib = (t: Result) => stdlib.Future.successful(t)
  final val transformationFunImproved = (t: Result) => improved.Future.successful(t)

  @tailrec private[this] final def nextS(i: Int, f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, f.flatMap(transformationFunStdlib)) } else { f }

  @tailrec private[this] final def nextI(i: Int, f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, f.flatMap(transformationFunImproved)) } else { f }

  @Benchmark final def stdlib_pre(): Boolean =
    await(nextS(recursion, stdlib_pre_s_p.future)(stdlibEC))

  @Benchmark final def stdlib_post(): Boolean = {
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = nextS(recursion, stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    await(nextI(recursion, improved_pre_s_p.future)(improvedEC))
  }

  @Benchmark final def improved_post(): Boolean = {
    val improved_post_p = improved.Promise[Result]()
    val f = nextI(recursion, improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class RecoverBenchmark extends OpBenchmark {
  final val recoverFunStdlib: PartialFunction[Throwable, Result] = { case _ => stdlibFailure.get }
  final val recoverFunImproved: PartialFunction[Throwable, Result] = { case _ => improvedFailure.get }

  @tailrec private[this] final def nextS(i: Int, f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, f.recover(recoverFunStdlib)) } else { f }

  @tailrec private[this] final def nextI(i: Int, f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, f.recover(recoverFunImproved)) } else { f }

  @Benchmark final def stdlib_pre(): Boolean =
    await(nextS(recursion, stdlib_pre_f_p.future)(stdlibEC))

  @Benchmark final def stdlib_post(): Boolean = {
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = nextS(recursion, stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibFailure)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    await(nextI(recursion, improved_pre_f_p.future)(improvedEC))
  }

  @Benchmark final def improved_post(): Boolean = {
    val improved_post_p = improved.Promise[Result]()
    val f = nextI(recursion, improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedFailure)
    await(f)
  }
}

class RecoverWithBenchmark extends OpBenchmark {
  final val recoverWithFunStdlib: PartialFunction[Throwable, stdlib.Future[Result]] = { case _ => stdlib_pre_f_p.future }
  final val recoverWithFunImproved: PartialFunction[Throwable, improved.Future[Result]] = { case _ => improved_pre_f_p.future }

  @tailrec private[this] final def nextS(i: Int, f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, f.recoverWith(recoverWithFunStdlib)) } else { f }

  @tailrec private[this] final def nextI(i: Int, f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, f.recoverWith(recoverWithFunImproved)) } else { f }

  @Benchmark final def stdlib_pre(): Boolean =
    await(nextS(recursion, stdlib_pre_f_p.future)(stdlibEC))

  @Benchmark final def stdlib_post(): Boolean = {
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = nextS(recursion, stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibFailure)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    await(nextI(recursion, improved_pre_f_p.future)(improvedEC))
  }

  @Benchmark final def improved_post(): Boolean = {
    val improved_post_p = improved.Promise[Result]()
    val f = nextI(recursion, improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedFailure)
    await(f)
  }
}


class ZipWithBenchmark extends OpBenchmark {
  final val transformationFun = (t1: Result, t2: Result) => t2

  @tailrec private[this] final def nextS(i: Int, f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, f.zipWith(f)(transformationFun)) } else { f }

  @tailrec private[this] final def nextI(i: Int, f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, f.zipWith(f)(transformationFun)) } else { f }

  @Benchmark final def stdlib_pre(): Boolean =
    await(nextS(recursion, stdlib_pre_s_p.future)(stdlibEC))

  @Benchmark final def stdlib_post(): Boolean = {
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = nextS(recursion, stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    await(nextI(recursion, improved_pre_s_p.future)(improvedEC))
  }

  @Benchmark final def improved_post(): Boolean = {
    val improved_post_p = improved.Promise[Result]()
    val f = nextI(recursion, improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class AndThenBenchmark extends OpBenchmark {
  final val effect: PartialFunction[Try[Result], Unit] = { case t: Try[Result] => () }
  
  @tailrec private[this] final def nextS(i: Int, f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, f.andThen(effect)) } else { f }

  @tailrec private[this] final def nextI(i: Int, f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, f.andThen(effect)) } else { f }

  @Benchmark final def stdlib_pre(): Boolean =
    await(nextS(recursion, stdlib_pre_s_p.future)(stdlibEC))

  @Benchmark final def stdlib_post(): Boolean = {
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = nextS(recursion, stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    await(nextI(recursion, improved_pre_s_p.future)(improvedEC))
  }

  @Benchmark final def improved_post(): Boolean = {
    val improved_post_p = improved.Promise[Result]()
    val f = nextI(recursion, improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class VariousBenchmark extends OpBenchmark {
  final val mapFun: Result => Result = _.toUpperCase
  final val stdlibFlatMapFun: Result => stdlib.Future[Result] = r => stdlib.Future.successful(r)
  final val improvedFlatMapFun: Result => improved.Future[Result] = r => improved.Future.successful(r)
  final val filterFun: Result => Boolean = _ ne null
  final val transformFun: Try[Result] => Try[Result] = _ => throw null
  final val recoverFun: PartialFunction[Throwable, Result] = { case _ => "OK" }

  @tailrec private[this] final def nextS(i: Int, f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, f.map(mapFun).flatMap(stdlibFlatMapFun).filter(filterFun).zipWith(f)((a, b) => a).transform(transformFun).recover(recoverFun)) } else { f }

  @tailrec private[this] final def nextI(i: Int, f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, f.map(mapFun).flatMap(improvedFlatMapFun).filter(filterFun).zipWith(f)((a, b) => a).transform(transformFun).recover(recoverFun)) } else { f }

  @Benchmark final def stdlib_pre(): Boolean =
    await(nextS(recursion, stdlib_pre_s_p.future)(stdlibEC))

  @Benchmark final def stdlib_post(): Boolean = {
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = nextS(recursion, stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    await(nextI(recursion, improved_pre_s_p.future)(improvedEC))
  }

  @Benchmark final def improved_post(): Boolean = {
    val improved_post_p = improved.Promise[Result]()
    val f = nextI(recursion, improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class LoopBenchmark extends OpBenchmark {
  val depth = 50
  val size  = 2000

  def pre_stdlib_loop(i: Int)(implicit ec: stdlib.ExecutionContext): stdlib.Future[Int] =
    if (i % depth == 0) stdlib.Future.successful(i + 1).flatMap(pre_stdlib_loop)
    else if (i < size) pre_stdlib_loop(i + 1).flatMap(stdlib.Future.successful)
    else stdlib.Future.successful(i)

  def pre_improved_loop(i: Int)(implicit ec: stdlib.ExecutionContext): improved.Future[Int] =
    if (i % depth == 0) improved.Future.successful(i + 1).flatMap(pre_improved_loop)
    else if (i < size) pre_improved_loop(i + 1).flatMap(improved.Future.successful)
    else improved.Future.successful(i)

  def post_stdlib_loop(i: Int)(implicit ec: stdlib.ExecutionContext): stdlib.Future[Int] =
    if (i % depth == 0) stdlib.Future(i + 1).flatMap(post_stdlib_loop)
    else if (i < size) post_stdlib_loop(i + 1).flatMap(i => stdlib.Future(i))
    else stdlib.Future(i)

  def post_improved_loop(i: Int)(implicit ec: stdlib.ExecutionContext): improved.Future[Int] =
    if (i % depth == 0) improved.Future(i + 1).flatMap(post_improved_loop)
    else if (i < size) post_improved_loop(i + 1).flatMap(i => improved.Future(i))
    else improved.Future(i)


  @Benchmark final def stdlib_pre(): Boolean = {
    implicit val ec = stdlibEC
    await(stdlib_pre_s_p.future.flatMap(s => pre_stdlib_loop(recursion).map(_ => s)))
  }

  @Benchmark final def stdlib_post(): Boolean = {
    implicit val ec = stdlibEC
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = stdlib_post_p.future.flatMap(s => post_stdlib_loop(recursion).map(_ => s))
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    implicit val ec = improvedEC
    await(improved_pre_s_p.future.flatMap(s => pre_improved_loop(recursion).map(_ => s)))
  }

  @Benchmark final def improved_post(): Boolean = {
    implicit val ec = improvedEC
    val improved_post_p = improved.Promise[Result]()
    val f = improved_post_p.future.flatMap(s => post_improved_loop(recursion).map(_ => s))
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class SequenceBenchmark extends OpBenchmark {

  @Benchmark final def stdlib_pre(): Boolean = {
    implicit val ec = stdlibEC
    await(stdlib.Future.sequence(1 to recursion map { _ => stdlib_pre_s_p.future }))
  }

  @Benchmark final def stdlib_post(): Boolean = {
    implicit val ec = stdlibEC
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = stdlib.Future.sequence(1 to recursion map { _ => stdlib_post_p.future })
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    implicit val ec = improvedEC
    await(improved.Future.sequence(1 to recursion map { _ => improved_pre_s_p.future }))
  }

  @Benchmark final def improved_post(): Boolean = {
        implicit val ec = improvedEC
    val improved_post_p = improved.Promise[Result]()
    val f = improved.Future.sequence(1 to recursion map { _ => improved_post_p.future })
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class FirstCompletedOfBenchmark extends OpBenchmark {

  @Benchmark final def stdlib_pre(): Boolean = {
    implicit val ec = stdlibEC
    await(stdlib.Future.firstCompletedOf(1 to recursion map { _ => stdlib_pre_s_p.future }))
  }

  @Benchmark final def stdlib_post(): Boolean = {
    implicit val ec = stdlibEC
    val stdlib_post_p = stdlib.Promise[Result]()
    val f = stdlib.Future.firstCompletedOf(1 to recursion map { _ => stdlib_post_p.future })
    stdlib_post_p.complete(stdlibSuccess)
    await(f)
  }

  @Benchmark final def improved_pre(): Boolean = {
    implicit val ec = improvedEC
    await(improved.Future.firstCompletedOf(1 to recursion map { _ => improved_pre_s_p.future }))
  }

  @Benchmark final def improved_post(): Boolean = {
        implicit val ec = improvedEC
    val improved_post_p = improved.Promise[Result]()
    val f = improved.Future.firstCompletedOf(1 to recursion map { _ => improved_post_p.future })
    improved_post_p.complete(improvedSuccess)
    await(f)
  }
}

class CompleteBenchmark extends OpBenchmark {
  
  @tailrec private[this] final def nextS(i: Int, p: stdlib.Promise[Result], r: Try[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, { p.tryComplete(r); p }, r) } else { p.future }

  @tailrec private[this] final def nextI(i: Int, p: improved.Promise[Result], r: Try[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, { p.tryComplete(r); p }, r) } else { p.future }

  @Benchmark final def stdlib_success(): Boolean = {
    val f = nextS(recursion, stdlib.Promise[Result](), stdlibSuccess)(stdlibEC)
    await(f)
  }

  @Benchmark final def improved_success(): Boolean = {
    val f = nextI(recursion, improved.Promise[Result](), improvedSuccess)(improvedEC)
    await(f)
  }

  @Benchmark final def stdlib_failure(): Boolean = {
    val f = nextS(recursion, stdlib.Promise[Result](), stdlibFailure)(stdlibEC)
    await(f)
  }

  @Benchmark final def improved_failure(): Boolean = {
    val f = nextI(recursion, improved.Promise[Result](), improvedFailure)(improvedEC)
    await(f)
  }
}

class CompleteWithBenchmark extends OpBenchmark {
  
  @tailrec private[this] final def nextS(i: Int, p: stdlib.Promise[Result], f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, { p.tryCompleteWith(f); p }, f) } else { p.future }

  @tailrec private[this] final def nextI(i: Int, p: improved.Promise[Result], f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, { p.tryCompleteWith(f); p }, f) } else { p.future }

  @Benchmark final def stdlib_success(): Boolean = {
    val f = nextS(recursion, stdlib.Promise[Result](), stdlib_pre_s_p.future)(stdlibEC)
    await(f)
  }

  @Benchmark final def improved_success(): Boolean = {
    val f = nextI(recursion, improved.Promise[Result](), improved_pre_s_p.future)(improvedEC)
    await(f)
  }

  @Benchmark final def stdlib_failure(): Boolean = {
    val f = nextS(recursion, stdlib.Promise[Result](), stdlib_pre_f_p.future)(stdlibEC)
    await(f)
  }

  @Benchmark final def improved_failure(): Boolean = {
    val f = nextI(recursion, improved.Promise[Result](), improved_pre_f_p.future)(improvedEC)
    await(f)
  }
}

class CallbackBenchmark extends OpBenchmark {
  class Callback(recursion: Int) extends CountDownLatch(recursion) with Function1[Try[Result], Unit] {
    override def apply(t:Try[Result]): Unit = this.countDown()
  }

  @tailrec private[this] final def nextS(i: Int, callback: Callback, f: stdlib.Future[Result])(implicit ec: stdlib.ExecutionContext): stdlib.Future[Result] =
      if (i > 0) { nextS(i - 1, callback, { f.onComplete(callback); f }) } else { f }

  @tailrec private[this] final def nextI(i: Int, callback: Callback, f: improved.Future[Result])(implicit ec: stdlib.ExecutionContext): improved.Future[Result] =
      if (i > 0) { nextI(i - 1, callback, { f.onComplete(callback); f }) } else { f }

  @Benchmark final def stdlib_pre(): Unit = {
    val callback = new Callback(recursion)
    nextS(recursion, callback, stdlib_pre_s_p.future)(stdlibEC)
    callback.await()
  }

  @Benchmark final def stdlib_post(): Unit = {
    val stdlib_post_p = stdlib.Promise[Result]()
    val callback = new Callback(recursion)
    nextS(recursion, callback, stdlib_post_p.future)(stdlibEC)
    stdlib_post_p.complete(stdlibSuccess)
    callback.await()
  }

  @Benchmark final def improved_pre(): Unit = {
    val callback = new Callback(recursion)
    nextI(recursion, callback,improved_pre_s_p.future)(improvedEC)
    callback.await()
  }

  @Benchmark final def improved_post(): Unit = {
    val improved_post_p = improved.Promise[Result]()
    val callback = new Callback(recursion)
    nextI(recursion, callback, improved_post_p.future)(improvedEC)
    improved_post_p.complete(improvedSuccess)
    callback.await()
  }
}