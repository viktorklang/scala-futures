name := "scala-futures"

scalaVersion := "2.11.8"

libraryDependencies += "junit" % "junit" % "4.11" % "test"

libraryDependencies += "com.novocode" % "junit-interface" % "0.10" % "test"

libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.6" % "test"

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")
