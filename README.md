#Improving Scala Futures

This repository is a working repository to improve and optimize Scala Futures.

To run the JMH benches:

```
sbt
project benches
jmh:runMain scala.future.BenchRunner JMH_ARGUMENTS_GO_HERE .*NAME_OF_BENCH.*
```

Examples:

To run the JMH benchmark for completing Promises:

```
jmh:runMain scala.future.BenchRunner .*CompletionBenchmark.*
```

To run the JMH benchmark for adding callbacks to Futures:

```
jmh:runMain scala.future.BenchRunner .*CallbackBenchmark.*
```

Please open Issues or submit Pull Requests to propose more benchmarks so we can attempt to ensure that there are no regressions and so that we can quantify optimization improvements across different hardware architectures and OS setups.