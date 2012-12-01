import sbt._
import Keys._

object Build extends Build {
  lazy val root = Project(
    "sbt-elasticbeanstalk",
    file("."),
    aggregate = Seq(sbtElasticBeanstalkPlugin, sbtElasticBeanstalkCore),
    settings = commonSettings ++ Seq(
      publishArtifact := false
    )
  )

  lazy val sbtElasticBeanstalkCore = Project(
    "sbt-elasticbeanstalk-core",
    file("core"),
    settings = commonSettings
  ).settings(
    libraryDependencies ++= Seq(
      "com.amazonaws" % "aws-java-sdk" % "1.3.26",
      "org.scalatest" %% "scalatest" % "1.8" % "test"
    )
  )

  lazy val sbtElasticBeanstalkPlugin = Project(
    "sbt-elasticbeanstalk-plugin",
    file("plugin"),
    settings = commonSettings
  ).settings(
    sbtPlugin := true,
    resolvers += Resolver.url("SQS Ivy", url("http://sqs.github.com/repo"))(Resolver.ivyStylePatterns),
    libraryDependencies ++= Seq(
      "com.github.play2war" % "play2-war-plugin" % "0.9a-SNAPSHOT" % "provided->default(compile)" extra ("scalaVersion" -> "2.9.2", "sbtVersion" -> "0.12")
    )
  ).dependsOn(sbtElasticBeanstalkCore).aggregate(sbtElasticBeanstalkCore)

  def commonSettings = Defaults.defaultSettings ++ Seq(
    organization := "com.blendlabsinc",
    version := "0.0.2-SNAPSHOT",
    scalaVersion := "2.9.2",
    scalacOptions ++= Seq("-unchecked", "-deprecation"),
    publishMavenStyle := false,
    publishArtifact in Test := false,
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false
  )
}
