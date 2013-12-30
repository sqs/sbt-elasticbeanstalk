package com.blendlabsinc.sbtelasticbeanstalk.core

import com.amazonaws.auth.PropertiesCredentials
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient
import com.amazonaws.services.s3.AmazonS3Client
import java.io.File

object AWS {
  lazy val awsCredentials = {
    val file = new File(new File(System.getProperty("user.home")), ".aws-credentials")
    if (!file.exists) {
      throw new Exception("AWS credentials file not found at " + file.getAbsolutePath + "\n\n" +
                          "Create a file at that path with the following contents:\n\n" +
                          "accessKey = <your AWS access key>\n" +
                          "secretKey = <your AWS secret key>\n")
    }
    new PropertiesCredentials(file)
  }
  
  def ec2Client(region: String): AmazonEC2Client = {
    val c = new AmazonEC2Client(awsCredentials)
    c.setEndpoint("https://ec2." + region + ".amazonaws.com")
    c
  }

  def elasticBeanstalkClient(region: String): AWSElasticBeanstalkClient = {
    val c = new AWSElasticBeanstalkClient(awsCredentials)
    c.setEndpoint("https://elasticbeanstalk." + region + ".amazonaws.com")
    c
  }

  def s3Client(region: String): AmazonS3Client = {
    val c = new AmazonS3Client(awsCredentials)
    c.setEndpoint("https://s3-" + region + ".amazonaws.com")
    c
  }
}
