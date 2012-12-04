package com.blendlabsinc.sbtelasticbeanstalk

import com.blendlabsinc.sbtelasticbeanstalk.ElasticBeanstalkKeys._
import sbt.Setting
import sbt.Keys.{ baseDirectory, commands }

trait ElasticBeanstalkSettings {
  this: ElasticBeanstalkCommands with ElasticBeanstalkAPICommands =>

  lazy val elasticBeanstalkSettings = Seq[Setting[_]](
    ebEnvironmentNameSuffix := { () =>
      val dateFormatter = new java.text.SimpleDateFormat("yyyyMMddHHmmssZ")
      dateFormatter.format(new java.util.Date) + System.getenv("USER").take(4) // TODO: ensure UTC
    },
    ebDeploy <<= ebDeployTask,
    ebWait <<= ebWaitForEnvironmentsTask,
    ebParentEnvironments <<= ebParentEnvironmentsTask,
    ebExistingEnvironments <<= ebExistingEnvironmentsTask,
    ebUploadSourceBundle <<= ebUploadSourceBundleTask,
    ebConfigPull <<= ebConfigPullTask,
    ebConfigPush <<= ebConfigPushTask,
    ebLocalConfig <<= ebLocalConfigReadTask,
    ebLocalConfigChanges <<= ebLocalConfigChangesTask,
    ebLocalConfigValidate <<= ebLocalConfigValidateTask,
    ebConfigDirectory <<= baseDirectory / "eb-deploy",
    ebApiDescribeApplications <<= ebApiDescribeApplicationsTask,
    ebApiDescribeEnvironments <<= ebApiDescribeEnvironmentsTask,
    commands ++= Seq(ebApiRestartAppServer),
    ebRequireJava6 := true
  )
}
