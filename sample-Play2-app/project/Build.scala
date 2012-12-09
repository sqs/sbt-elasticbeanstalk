import sbt._
import Keys._
import play.Project._
import com.blendlabsinc.sbtelasticbeanstalk.{ ElasticBeanstalk, Deployment }
import com.blendlabsinc.sbtelasticbeanstalk.ElasticBeanstalkKeys._
import com.github.play2war.plugin._

object ApplicationBuild extends Build {
  val appName         = "sbt-elasticbeanstalk-sample-Play2-app"
  val appVersion      = "1.0-SNAPSHOT"

  val appDependencies = Seq()

  val main = play.Project(appName, appVersion, appDependencies).settings(
    resolvers += Resolver.url("SQS Ivy", url("http://sqs.github.com/repo"))(Resolver.ivyStylePatterns),
    Play2WarKeys.servletVersion := "3.0",
    ebS3BucketName := "sbt-elasticbeanstalk-test",
    ebDeployments := Seq(
      Deployment(
        appName = "sbt-elasticbeanstalk-sample-Play2-app",
        envBaseName = "test-swap",
        templateName = "play2-sample",
        cname = "sbt-eb-sample-play2-swap.elasticbeanstalk.com",
        environmentVariables = Map("MyFavoriteColor" -> "blue")
      )
    ),
    ebRegion := "us-west-1"
  )
    .settings(ElasticBeanstalk.elasticBeanstalkSettings: _*)
    .settings(Play2WarPlugin.play2WarSettings: _*)
}
