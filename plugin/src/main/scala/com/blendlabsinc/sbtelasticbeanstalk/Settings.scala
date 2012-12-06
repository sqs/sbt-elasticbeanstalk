package com.blendlabsinc.sbtelasticbeanstalk

import com.blendlabsinc.sbtelasticbeanstalk.ElasticBeanstalkKeys._
import sbt.{ Setting, Hash }
import sbt.Keys.{ baseDirectory, commands }

trait ElasticBeanstalkSettings {
  this: ElasticBeanstalkCommands with ElasticBeanstalkAPICommands =>

  val sessionId = new java.math.BigInteger(130, new java.security.SecureRandom()).toString(32) // HACK TODO
  lazy val elasticBeanstalkSettings = Seq[Setting[_]](
    ebEnvironmentNameSuffix := { (name) =>
      val maxLen = 23
      val uniq = sessionId
      if (name.length > (23 - 7)) throw new Exception("environment name too long: " + name)
      name + "-" + System.getenv("USER").take(3) + uniq.take(3)
    },
    ebDeploy <<= ebDeployTask,
    ebSetUpEnvForAppVersion <<= ebSetUpEnvForAppVersionTask,
    ebWait <<= ebWaitForEnvironmentsTask,
    ebParentEnvironments <<= ebParentEnvironmentsTask,
    ebTargetEnvironments <<= ebTargetEnvironmentsTask,
    ebExistingEnvironments <<= ebExistingEnvironmentsTask,
    ebCleanEnvironments <<= ebCleanEnvironmentsTask,
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
