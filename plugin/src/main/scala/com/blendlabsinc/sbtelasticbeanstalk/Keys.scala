package com.blendlabsinc.sbtelasticbeanstalk

import sbt.{ SettingKey, TaskKey }

object ElasticBeanstalkKeys {
  val ebS3BucketName = SettingKey[String]("ebS3BucketName", "S3 bucket which should contain uploaded WAR files")
  val ebAppName = SettingKey[String]("ebAppName", "Elastic Beanstalk application name")
  val ebEnvironmentName = SettingKey[String]("ebEnvironmentName", "Elastic Beanstalk environment name")

  val ebRegion = SettingKey[String]("ebRegion", "Elastic Beanstalk region (e.g., us-west-1)")

  val ebDeploy = TaskKey[Unit]("eb-deploy", "Deploy the application WAR to Elastic Beanstalk")
}
