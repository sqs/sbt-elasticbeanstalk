package com.blendlabsinc.sbtelasticbeanstalk

import sbt.Keys.{ version, streams }

trait ElasticBeanstalkCommands {
  val ebDeployTask = (version, streams) map {
    (version, s) =>
      s.log.info("ElasticBeanstalk deploy")
  }
}
