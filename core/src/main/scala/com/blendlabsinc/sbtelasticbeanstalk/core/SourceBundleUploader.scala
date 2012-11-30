package com.blendlabsinc.sbtelasticbeanstalk.core

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model._
import com.amazonaws.services.elasticbeanstalk.model._
import java.io.File
import java.util.Date
import java.text.SimpleDateFormat

class SourceBundleUploader(
  bundleFile: File,
  s3BucketName: String,
  awsCredentials: AWSCredentials
) {
  private val s3 = new AmazonS3Client(awsCredentials)
  private val dateFormatter = new SimpleDateFormat("yyMMddHHmmssZ")

  // TODO: make async
  def upload(): S3Location = {
    val key = bundleFile.getName + "-" + System.getenv("USER") + "-" + dateFormatter.format(new Date)
    s3.putObject(new PutObjectRequest(
      s3BucketName,
      key,
      bundleFile
    ))
    new S3Location(s3BucketName, key)
  }
}
