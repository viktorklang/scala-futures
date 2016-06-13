package scala.future

import org.scalatest.{ WordSpec, Assertions }
import org.scalatest.matchers.MustMatchers

class ScalaFutureSpec extends WordSpec with MustMatchers {

  "A Scala Future" should {
    "be awesome" in {
      true must be === true
    }
  }

}