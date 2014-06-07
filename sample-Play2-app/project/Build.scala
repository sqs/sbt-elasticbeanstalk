import sbt._
import Keys._
import play.Project._
import com.joescii.sbtelasticbeanstalk.{ ElasticBeanstalk, Deployment }
import com.joescii.sbtelasticbeanstalk.ElasticBeanstalkKeys._
import com.github.play2war.plugin._

object ApplicationBuild extends Build {
  val appName         = "sbt-elasticbeanstalk-sample-Play2-app"
  val appVersion      = "1.0-SNAPSHOT"

  val appDependencies = Seq()

  val main = play.Project(appName, appVersion, appDependencies).settings(
    Play2WarKeys.servletVersion := "3.0",
    ebAppBundle <<= Play2WarKeys.war,
    ebS3BucketName := "sbt-elasticbeanstalk-test",
    ebDeployments := Seq(
      Deployment(
        appName = "sbteb-sample",
        envBaseName = "test-swap",
        templateName = "play2-sample",
        cname = "sbt-eb-sample-play2-swap.elasticbeanstalk.com",
        environmentVariables = Map("MyFavoriteColor" -> "blue")
      )
    ),
    ebRegion := "us-west-2"
  )
    .settings(ElasticBeanstalk.elasticBeanstalkSettings: _*)
    .settings(Play2WarPlugin.play2WarSettings: _*)
    .settings(ebRequireJava6 := false)

  val pythonApp = Project(
    id = "python-app",
    base = file("python-app"),
    settings = Project.defaultSettings ++ ElasticBeanstalk.elasticBeanstalkSettings ++ Seq(
      ebRequireJava6 := false,
      ebS3BucketName := "sbt-elasticbeanstalk-test",
      ebRegion := "us-west-2",
      ebAppBundle := file("python-app"), // If you pass in a directory, it will be zipped up and deployed.
      ebDeployments := Seq(
        Deployment(
          appName = "sbteb-sample",
          envBaseName = "test-python-app",
          templateName = "python-app",
          cname = "sbt-eb-sample-python-app.elasticbeanstalk.com",
          solutionStackName = "64bit Amazon Linux running Python"
        )
      )
    )
  )
}
