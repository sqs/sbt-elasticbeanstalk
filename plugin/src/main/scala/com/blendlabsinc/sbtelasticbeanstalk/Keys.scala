package com.blendlabsinc.sbtelasticbeanstalk

import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient
import com.amazonaws.services.elasticbeanstalk.model._
import java.io.File
import sbt.{ InputKey, SettingKey, TaskKey }

case class Deployment(
  appName: String,
  envBaseName: String,
  templateName: String,
  cname: String,
  environmentVariables: Map[String, String] = Map(),
  solutionStackName: String = "64bit Amazon Linux running Tomcat 7"
) {
  if (cname.toLowerCase != cname) throw new Exception("Deployment CNAME should be lowercase for '" + cname + "'.")

  def envNamePrefix: String = envBaseName + "-"

  def environmentCorrespondsToThisDeployment(env: EnvironmentDescription): Boolean =
    env.getEnvironmentName.startsWith(envNamePrefix)
}

object ElasticBeanstalkKeys {
  val ebS3BucketName = SettingKey[String]("ebS3BucketName", "S3 bucket which should contain uploaded WAR files")
  val ebDeployments = SettingKey[Seq[Deployment]]("eb-deployments", "List of Elastic Beanstalk deployment targets")

  val ebAppBundle = TaskKey[File]("eb-app-bundle", "The application file ('source bundle' in AWS terms) to deploy.")

  val ebEnvironmentNameSuffix = SettingKey[Function1[String,String]]("eb-environment-name-suffix", "Function that returns an environment name suffixed with a unique string")

  val ebRegion = SettingKey[String]("ebRegion", "Elastic Beanstalk region (e.g., us-west-1)")

  val ebDeploy = TaskKey[Unit]("eb-deploy", "Deploy the application WAR to Elastic Beanstalk")
  val ebQuickUpdate = InputKey[Unit]("eb-quick-update", "Update the application WAR in-place on a running server.")
  val ebCreateVersion = TaskKey[Map[String,ApplicationVersionDescription]]("eb-create-version", "Creates a new application version in the configured environment.")
  // TODO: Determine if this task has the same intent as ebQuickUpdate
  val ebUpdateVersion = TaskKey[Map[Deployment, EnvironmentDescription]]("eb-update-version", "Uploads a new application version and updates the configured environment with the version.")
  val ebWait = TaskKey[Unit]("eb-wait", "Wait for all project environments to be Ready and Green")

  val ebSetUpEnvForAppVersion = TaskKey[Map[Deployment,EnvironmentDescription]]("eb-setup-env-for-app-version", "Sets up a new environment for the WAR file.")

  val ebConfigPull = TaskKey[List[File]]("eb-config-pull", "Downloads existing configurations for all project environments")
  val ebConfigPush = TaskKey[Unit]("eb-config-push", "Updates configurations for all project environments using local configs (that were pulled with eb-config-pull)")

  val ebParentEnvironments = TaskKey[Map[Deployment,EnvironmentDescription]]("eb-parent-environments", "Returns all existing environments that correspond to project environments.")

  val ebTargetEnvironments = TaskKey[Map[Deployment,EnvironmentDescription]]("eb-target-environments", "Returns an the EnvironmentDescription describing the environment that should be created and deployed to.")

  val ebExistingEnvironments = TaskKey[Map[Deployment,List[EnvironmentDescription]]]("eb-existing-environments", "Describes all existing environments (i.e., that are on Elastic Beanstalk) that correspond to project environments.")

  val ebCleanEnvironments = TaskKey[Unit]("eb-clean", "Terminates all old and unused environments")
  val ebCleanAppVersions = TaskKey[Unit]("eb-clean-app-versions", "Deletes old app versions")

  val ebUploadSourceBundle = TaskKey[S3Location]("eb-upload-source-bundle", "Uploads the WAR source bundle to S3")

  val ebConfigDirectory = SettingKey[File]("eb-config-directory", "Where EB configs are pulled to and pushed from")

  val ebApiDescribeApplications = TaskKey[List[ApplicationDescription]]("eb-api-describe-applications", "Returns the descriptions of existing applications")
  val ebApiDescribeEnvironments = TaskKey[List[EnvironmentDescription]]("eb-api-describe-environments", "Returns descriptions for existing environments")

  val ec2Client = TaskKey[AmazonEC2Client]("eb-ec2-client")
  val ebClient = TaskKey[AWSElasticBeanstalkClient]("eb-client")

  val ebNotify = SettingKey[Function3[Deployment, EnvironmentDescription, String, Unit]]("eb-notify", "Notification function called after successful deployments and quick-updates")

  // Debug
  val ebRequireJava6 = SettingKey[Boolean]("eb-require-java6", "Require Java6 to deploy WAR (as of Dec 2012, Java7 is incompatible with EB)")
}
