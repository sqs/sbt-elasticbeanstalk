import sbt._
import Keys._

object Build extends Build {
  lazy val root = Project(
    "sbt-elasticbeanstalk",
    file("."),
    aggregate = Seq(sbtElasticBeanstalkPlugin, sbtElasticBeanstalkCore),
    settings = commonSettings ++ Seq(
      libraryDependencies ++= Seq(
        "com.amazonaws" % "aws-java-sdk" % "1.3.26"
      ),
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
      "com.fasterxml.jackson.core" % "jackson-core" % "2.1.1",
      "com.fasterxml.jackson.core" % "jackson-databind" % "2.1.1",
      "net.schmizz" % "sshj" % "0.8.1",
      "org.bouncycastle" % "bcprov-jdk16" % "1.46" 
    )
  ).dependsOn(sbtElasticBeanstalkCore).aggregate(sbtElasticBeanstalkCore)

  def commonSettings = Defaults.defaultSettings ++ Seq(
    organization := "com.blendlabsinc",
    version := "0.0.7-SNAPSHOT",
    scalaVersion := "2.9.2",
    scalacOptions ++= Seq("-unchecked", "-deprecation"),
    publishMavenStyle := false,
    publishArtifact in Test := false,
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in (Compile, packageSrc) := false
  )
}
