package com.blendlabsinc.sbtelasticbeanstalk.core

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.elasticbeanstalk.model._
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient

class Deployer(
  appName: String,
  envName: String,
  versionLabel: String,
  bundleS3Location: S3Location,
  awsCredentials: AWSCredentials,
  region: String
) {
  private val eb = new AWSElasticBeanstalkClient(awsCredentials)
  eb.setEndpoint("https://elasticbeanstalk." + region + ".amazonaws.com")

  def deploy(): UpdateEnvironmentResult = {
    val versionDesc = createAppVersion()
    updateEnvironmentToUseNewVersion(versionDesc)
  }

  private def createAppVersion(): ApplicationVersionDescription = {
    eb.createApplicationVersion(
      new CreateApplicationVersionRequest()
        .withApplicationName(appName)
        .withVersionLabel(versionLabel)
        .withSourceBundle(bundleS3Location)
        .withDescription("Deployed by " + System.getenv("USER"))
    ).getApplicationVersion
  }

  private def updateEnvironmentToUseNewVersion(
    newVersion: ApplicationVersionDescription
  ): UpdateEnvironmentResult = {
    eb.updateEnvironment(
      new UpdateEnvironmentRequest()
        .withEnvironmentName(envName)
        .withVersionLabel(newVersion.getVersionLabel)
    )
  }
}
