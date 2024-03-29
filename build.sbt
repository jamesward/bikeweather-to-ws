enablePlugins(PlayScala)

name := "bikeweather-to-ws"

scalaVersion := "2.13.1"

libraryDependencies ++= Seq(
  guice,
  "com.google.cloud" % "google-cloud-bigquery" % "1.87.0",
)

sources in (Compile,doc) := Seq.empty

javaOptions in Universal ++= Seq(
  "-Dpidfile.path=/dev/null"
)

Global / cancelable := false

dockerBaseImage := "adoptopenjdk/openjdk8"
