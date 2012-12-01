package com.blendlabsinc.sbtelasticbeanstalk

import com.blendlabsinc.sbtelasticbeanstalk.ElasticBeanstalkKeys._
import sbt.Setting

trait ElasticBeanstalkSettings {
  this: ElasticBeanstalkCommands =>

  lazy val elasticBeanstalkSettings = Seq[Setting[_]](
    ebDeploy <<= ebDeployTask,
    ebRequireJava6 := true
  )
}
