name := "scala-futures"

scalaVersion := "2.12.1"

lazy val root = project in file(".")

lazy val benches = project.in(file("benches")).dependsOn(root)

scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.8", "-feature", "-unchecked", "-deprecation", "-Xlog-reflective-calls", "-Xlint", "-opt:l:project")

libraryDependencies += "junit" % "junit" % "4.11" % "test"

libraryDependencies += "com.novocode" % "junit-interface" % "0.10" % "test"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.0" % "test"

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")

enablePlugins(JmhPlugin)
