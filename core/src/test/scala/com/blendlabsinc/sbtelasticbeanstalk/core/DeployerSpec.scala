package com.blendlabsinc.sbtelasticbeanstalk.core

import com.amazonaws.services.elasticbeanstalk.model._
import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers

class DeployerSpec extends Spec with ShouldMatchers {
  // Assumes that you have manually uploaded a file with key "sample.war" to the test bucket
  val bundleS3Location = new S3Location(TestCommon.s3BucketName, TestCommon.warName)
  val appName = "sbt-elasticbeanstalk-test"
  val envName = "sbt-eb-test"
  val region = "us-west-1"
  val versionLabel = bundleS3Location.getS3Key + "-test-" + System.currentTimeMillis.toString

  it("should deploy") {
    val d = new Deployer(appName, envName, versionLabel, bundleS3Location, AWS.awsCredentials, region)
    val updateEnvResult = d.deploy()
    updateEnvResult.getVersionLabel should equal (versionLabel)
  }
}
