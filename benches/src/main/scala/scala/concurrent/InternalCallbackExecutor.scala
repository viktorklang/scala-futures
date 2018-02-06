package scala.concurrent

object InternalCallbackExecutor {
  def apply(): ExecutionContext = Future.InternalCallbackExecutor
}

trait BatchingEC extends BatchingExecutor