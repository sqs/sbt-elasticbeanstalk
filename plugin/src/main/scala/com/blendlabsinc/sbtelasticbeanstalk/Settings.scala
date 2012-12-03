package com.blendlabsinc.sbtelasticbeanstalk

import com.blendlabsinc.sbtelasticbeanstalk.ElasticBeanstalkKeys._
import sbt.Setting
import sbt.Keys.{ baseDirectory, commands }

trait ElasticBeanstalkSettings {
  this: ElasticBeanstalkCommands with ElasticBeanstalkAPICommands =>

  lazy val elasticBeanstalkSettings = Seq[Setting[_]](
    ebDeploy <<= ebDeployTask,
    ebWait <<= ebWaitForEnvironmentsTask,
    ebDescribeEnvironments <<= ebDescribeEnvironmentsTask,
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
