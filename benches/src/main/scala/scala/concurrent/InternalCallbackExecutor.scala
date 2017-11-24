package scala.concurrent

object InternalCallbackExecutor {
  def apply(): ExecutionContext = Future.InternalCallbackExecutor
}