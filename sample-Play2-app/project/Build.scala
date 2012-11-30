import sbt._
import Keys._
import play.Project._
import com.blendlabsinc.sbtelasticbeanstalk.ElasticBeanstalk.elasticBeanstalkSettings
import com.blendlabsinc.sbtelasticbeanstalk.ElasticBeanstalkKeys._
import com.github.play2war.plugin._

object ApplicationBuild extends Build {
  val appName         = "sbt-elasticbeanstalk-sample-Play2-app"
  val appVersion      = "1.0-SNAPSHOT"

  val appDependencies = Seq()

  val main = play.Project(appName, appVersion, appDependencies).settings(
    Play2WarKeys.servletVersion := "3.0",
    ebS3BucketName := "sbt-elasticbeanstalk-test",
    ebAppName := "sbt-elasticbeanstalk-sample-Play2-app",
    ebEnvironmentName := "sbt-eb-sample-Play2",
    ebRegion := "us-west-1"
  )
    .settings(elasticBeanstalkSettings: _*)
    .settings(Play2WarPlugin.play2WarSettings: _*)
}
