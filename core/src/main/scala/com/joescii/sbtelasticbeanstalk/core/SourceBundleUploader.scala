package com.joescii.sbtelasticbeanstalk.core

import com.amazonaws.auth.AWSCredentials
//import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model._
import com.amazonaws.services.elasticbeanstalk.model._
import com.amazonaws.services.s3.transfer._
import java.io.File
import java.util.Date
import java.text.SimpleDateFormat

class SourceBundleUploader(
  bundleFile: File,
  s3BucketName: String,
  awsCredentials: AWSCredentials
) {
  private val dateFormatter = new SimpleDateFormat("yyyyMMddHHmmssZ")

  // TODO: make async
  def upload(): S3Location = {
    val key = bundleFile.getName + "-" + System.getProperty("user.name") + "-" + dateFormatter.format(new Date)

    val tx = new TransferManager(awsCredentials)

    var upload: Upload = null

    val progressListener = new ProgressListener() {
      private def printProgress(s: String) {
        val show = System.getProperty("sbt.elasticbeanstalk.showprogressbar")
        if (show == null || show == "true") {
          print(s)
        }
      }

      def progressChanged(event: ProgressEvent) {
        val progress = upload.getProgress
        def bytesToMB(bytes: Long): Double = (bytes.toDouble / 1024 / 1024)
        printProgress("\rTransferred: %.1f/%.1f MB (%.1f%%)".format(
          bytesToMB(progress.getBytesTransfered),
          bytesToMB(progress.getTotalBytesToTransfer),
          progress.getPercentTransfered
        ))

        val code = event.getEventCode
        if (code == ProgressEvent.COMPLETED_EVENT_CODE || code == ProgressEvent.FAILED_EVENT_CODE) {
          printProgress("\n")
        }
      }
    }

    val req = new PutObjectRequest(
      s3BucketName,
      key,
      bundleFile
    ).withProgressListener(progressListener)

    upload = tx.upload(req)
    upload.waitForUploadResult()
    new S3Location(s3BucketName, key)
  }
}
