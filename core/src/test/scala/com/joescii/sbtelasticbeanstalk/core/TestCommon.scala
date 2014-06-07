package com.joescii.sbtelasticbeanstalk.core

import java.io.File

object TestCommon {
  val s3BucketName = "sbt-elasticbeanstalk-test"

  val warName = "sample.war"
  val warFile = new File(getClass.getResource("/" + warName).getPath)
}
