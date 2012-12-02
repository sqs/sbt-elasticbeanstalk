package com.blendlabsinc.sbtelasticbeanstalk.core

import com.amazonaws.auth.PropertiesCredentials
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient
import java.io.File

object AWS {
  lazy val awsCredentials = {
    val file = new File(new File(System.getenv("HOME")), ".aws-credentials")
    if (!file.exists) {
      throw new Exception("AWS credentials file not found at " + file.getAbsolutePath + "\n\n" +
                          "Create a file at that path with the following contents:\n\n" +
                          "accessKey = <your AWS access key>\n" +
                          "secretKey = <your AWS secret key>\n")
    }
    new PropertiesCredentials(file)
  }

  def elasticBeanstalkClient(region: String): AWSElasticBeanstalkClient = {
    val c = new AWSElasticBeanstalkClient(awsCredentials)
    c.setEndpoint("https://elasticbeanstalk." + region + ".amazonaws.com")
    c
  }
}
