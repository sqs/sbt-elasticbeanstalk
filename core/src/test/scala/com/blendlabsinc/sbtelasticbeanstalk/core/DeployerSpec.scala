package com.blendlabsinc.sbtelasticbeanstalk.core

import com.amazonaws.services.elasticbeanstalk.model._
import scala.collection.JavaConverters._
import org.scalatest.FunSpec
import org.scalatest.matchers.ShouldMatchers

class DeployerSpec extends FunSpec with ShouldMatchers {
  // Assumes that you have manually uploaded a file with key "sample.war" to the test bucket
  val bundleS3Location = new S3Location(TestCommon.s3BucketName, TestCommon.warName)
  val appName = "sbt-elasticbeanstalk-test"
  val envName = "sbt-eb-test"
  val region = "us-west-1"
  val versionLabel = bundleS3Location.getS3Key + "-test-" + System.currentTimeMillis.toString

  val d = new Deployer(appName, envName, AWS.awsCredentials, region)

  it("should deploy") {
    val envMap = Map[String,String]("TestEnv" -> scala.util.Random.nextInt().toString)
    val updateEnvResult = d.deploy(versionLabel, bundleS3Location, envMap)
    updateEnvResult.getVersionLabel should equal (versionLabel)
    //   // This code needs to wait until the environemnt has loaded to test correctly, since
    //   // you can't describe environment settings when its status is Grey.
    //   val cfg = d.eb.describeConfigurationSettings(new DescribeConfigurationSettingsRequest(appName).withEnvironmentName(envName))
    //   val allSettings = cfg.getConfigurationSettings.asScala.flatMap(_.getOptionSettings.asScala)
    //   allSettings.find { s =>
    //     s.getNamespace == "aws:elasticbeanstalk:application:environment" &&
    //       s.getOptionName == "TestEnv" }.get.getValue should equal (envMap("TestEnv"))
  }
}
