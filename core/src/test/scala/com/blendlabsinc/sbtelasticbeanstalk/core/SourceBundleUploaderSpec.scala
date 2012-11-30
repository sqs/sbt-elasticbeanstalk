package com.blendlabsinc.sbtelasticbeanstalk.core

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers

class SourceBundleUploaderSpec extends Spec with ShouldMatchers {
  it("should upload") {
    val u = new SourceBundleUploader(TestCommon.warFile, TestCommon.s3BucketName, AWS.awsCredentials)
    val s3Location = u.upload()
    s3Location.getS3Bucket should equal (TestCommon.s3BucketName)
    s3Location.getS3Key should include (TestCommon.warName)
    s3Location.getS3Key should include (System.getenv("USER"))
  }
}
