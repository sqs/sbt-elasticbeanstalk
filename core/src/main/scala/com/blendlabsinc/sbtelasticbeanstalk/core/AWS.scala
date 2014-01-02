package com.blendlabsinc.sbtelasticbeanstalk.core

import com.amazonaws.auth.{BasicAWSCredentials, PropertiesCredentials}
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient
import com.amazonaws.services.s3.AmazonS3Client
import java.io.File
import scala.sys.SystemProperties

object AWS {
  lazy val awsCredentials = {
    val props = new SystemProperties

    val file = new File(new File(System.getProperty("user.home")), ".aws-credentials")
    val accessKey = props.get("accessKey")
    val secretKey = props.get("secretKey")

    if (file.exists) {
      new PropertiesCredentials(file)
    }
    else if (accessKey.isDefined && secretKey.isDefined) {
      new BasicAWSCredentials(accessKey.get, secretKey.get)
    }
    else {
      throw new Exception("AWS credentials file not found at " + file.getAbsolutePath +
        " nor are System properties 'accessKey' and 'secretKey' defined.\n\n" +
        "Either define System properties 'accessKey' and 'secretKey' or acreate a file at that path with the following contents:\n\n" +
        "accessKey = <your AWS access key>\n" +
        "secretKey = <your AWS secret key>\n\n")
    }
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
