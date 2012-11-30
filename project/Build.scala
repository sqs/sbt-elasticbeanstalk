import sbt._
import Keys._

object Build extends Build {
  lazy val root = Project(
    "sbt-elasticbeanstalk",
    file("."),
    aggregate = Seq(sbtElasticBeanstalkPlugin),
    settings = commonSettings ++ Seq(
      publishArtifact := false
    )
  )

  lazy val sbtElasticBeanstalkPlugin = Project(
    "sbt-elasticbeanstalk-plugin",
    file("plugin"),
    settings = commonSettings
  ).settings(
    sbtPlugin := true,
    libraryDependencies ++= Seq(
      "com.amazonaws" % "aws-java-sdk" % "1.3.26"
    )
  )

  def commonSettings = Defaults.defaultSettings ++ Seq(
    organization := "com.blendlabsinc",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "2.9.2",
    scalacOptions ++= Seq("-unchecked", "-deprecation"),
    publishMavenStyle := false,
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false
  )
}
