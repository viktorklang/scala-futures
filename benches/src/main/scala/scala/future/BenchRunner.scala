/**
 * Adapted from: https://github.com/akka/akka/blob/master/akka-bench-jmh/src/main/scala/akka/BenchRunner.scala
 **/

package scala.future

import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.CommandLineOptions

object BenchRunner {
  def main(args: Array[String]): Unit = {
    import scala.collection.JavaConversions._
    import scala.collection.immutable

    val args2 = args.toList.flatMap {
      case "quick" => "-i 1 -wi 1 -f1 -t1".split(' ').toList
      case "full" => "-i 10 -wi 4 -f3 -t1".split(' ').toList
      case "long" => "-i 1000 -wi 4 -f3 -t1".split(' ').toList
      case "jitwatch" => "-jvmArgs=-XX:+UnlockDiagnosticVMOptions -XX:+TraceClassLoading -XX:+LogCompilation" :: Nil
      case other => other :: Nil
    }

    val opts = new CommandLineOptions(args2: _*)
    val results = new Runner(opts).run()

    val report = results.map { result: RunResult ⇒
      val bench = result.getParams.getBenchmark
      val params = result.getParams.getParamsKeys.map(key => s"$key=${result.getParams.getParam(key)}").mkString("_")
      val score = result.getAggregatedResult.getPrimaryResult.getScore.round
      val unit = result.getAggregatedResult.getPrimaryResult.getScoreUnit
      s"\t${bench}_${params}\t$score\t$unit"
    }

    report.to[immutable.SortedSet].foreach(println)
  }
}
