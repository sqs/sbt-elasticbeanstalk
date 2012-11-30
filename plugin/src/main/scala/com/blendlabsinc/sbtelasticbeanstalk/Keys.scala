package com.blendlabsinc.sbtelasticbeanstalk

import sbt.TaskKey

object ElasticBeanstalkKeys {
  val ebDeploy = TaskKey[Unit]("eb-deploy", "Deploy the application WAR to Elastic Beanstalk")
}
