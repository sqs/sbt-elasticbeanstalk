package com.blendlabsinc.sbtelasticbeanstalk

import com.amazonaws.services.elasticbeanstalk.model._
import java.io.File
import sbt.{ SettingKey, TaskKey }

case class Deployment(
  appName: String,
  environmentName: String,
  environmentVariables: Map[String, String] = Map()
)

object ElasticBeanstalkKeys {
  val ebS3BucketName = SettingKey[String]("ebS3BucketName", "S3 bucket which should contain uploaded WAR files")
  val ebDeployments = SettingKey[Seq[Deployment]]("eb-deployments", "List of Elastic Beanstalk deployment targets")

  val ebRegion = SettingKey[String]("ebRegion", "Elastic Beanstalk region (e.g., us-west-1)")

  val ebDeploy = TaskKey[Unit]("eb-deploy", "Deploy the application WAR to Elastic Beanstalk")
  val ebWait = TaskKey[Unit]("eb-wait", "Wait for all project environments to be Ready and Green")

  val ebDescribeEnvironments = TaskKey[List[EnvironmentDescription]]("eb-describe-environments", "Describes all project environments")

  val ebConfigDirectory = SettingKey[File]("eb-config-directory", "Where EB configs are pulled to and pushed from")
  val ebConfigPull = TaskKey[List[File]]("eb-config-pull", "Downloads existing configurations for all project environments") // TODO: also pull app configs and templates

  val ebApiDescribeApplications = TaskKey[List[ApplicationDescription]]("eb-api-describe-applications", "Returns the descriptions of existing applications")
  val ebApiDescribeEnvironments = TaskKey[List[EnvironmentDescription]]("eb-api-describe-environments", "Returns descriptions for existing environments")

  // Debug
  val ebRequireJava6 = SettingKey[Boolean]("eb-require-java6", "Require Java6 to deploy WAR (as of Dec 2012, Java7 is incompatible with EB)")
}
