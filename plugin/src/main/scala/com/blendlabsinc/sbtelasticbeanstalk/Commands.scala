package com.blendlabsinc.sbtelasticbeanstalk

import com.blendlabsinc.sbtelasticbeanstalk.{ ElasticBeanstalkKeys => eb }
import com.blendlabsinc.sbtelasticbeanstalk.core.{ AWS, Deployer, SourceBundleUploader }
import com.github.play2war.plugin.Play2WarKeys
import sbt.Keys.{ version, streams }

trait ElasticBeanstalkCommands {
  val ebDeployTask = (Play2WarKeys.war, eb.ebS3BucketName, eb.ebDeployments, eb.ebRegion, eb.ebRequireJava6, streams) map {
    (war, s3BucketName, ebDeployments, ebRegion, ebRequireJava6, s) => {
      if (ebRequireJava6 && System.getProperty("java.specification.version") != "1.6") {
        throw new Exception(
          "ebRequireJava6 := true, but you are currently running in Java " +
          System.getProperty("java.specification.version") + ". As of Dec 2012, " +
          "Elastic Beanstalk is incompatible with Java7. You should use Java6 to compile " +
          "and deploy WARs. You can also set ebRequireJava6 := false in " +
          "your sbt settings to suppress this warning, but beware that Java7-compiled WARs " +
          "currently fail in strange ways on Elastic Beanstalk."
        )                    
      }


      s.log.info("Uploading " + war.getName + " (" + (war.length/1024/1024) + " MB) " +
                 "to Amazon S3 bucket '" + s3BucketName + "'")
      val u = new SourceBundleUploader(war, s3BucketName, AWS.awsCredentials)
      val bundleLocation = u.upload()

      s.log.info("WAR file upload complete.")

      val versionLabel = bundleLocation.getS3Key

      for (deployment <- ebDeployments) {
        s.log.info(
          "Deploying to Elastic Beanstalk:\n" + 
          "  WAR file: " + war.getName + "\n" +
          "  EB app version label: " + versionLabel + "\n" +
          "  EB app: " + deployment.appName + "\n" +
          "  EB environment: " + deployment.environmentName + "\n" +
          "  Region: " + ebRegion + "\n" +
          "  Environment vars: " + deployment.environmentVariables.toString + "\n\n"
        )
        val d = new Deployer(
          deployment.appName,
          deployment.environmentName,
          AWS.elasticBeanstalkClient(ebRegion)
        )
        val res = d.deploy(versionLabel, bundleLocation, deployment.environmentVariables)

        s.log.info("Elastic Beanstalk deployment complete.\n" +
                   "URL: http://" + res.getCNAME() + "\n" +
                   "Status: " + res.getHealth())
      }
    }
  }
}
