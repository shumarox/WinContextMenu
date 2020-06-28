name := "WinContextMenu"
version := "0.0.1"

scalaVersion := "2.13.3"

crossPaths := false

scalacOptions ++= Seq("-encoding", "UTF-8")

autoScalaLibrary := false

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-library" % scalaVersion.value,
  "net.java.dev.jna" % "jna" % "4.5.2",
  "net.java.dev.jna" % "jna-platform" % "4.5.2",
)
