name := "CNA"

version := "0.1"

scalaVersion := "2.12.11"

val sparkVersion = "2.4.5"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  "org.apache.spark" %% "spark-graphx" % sparkVersion,
  "net.liftweb" %% "lift-json" % "3.4.1"
)
