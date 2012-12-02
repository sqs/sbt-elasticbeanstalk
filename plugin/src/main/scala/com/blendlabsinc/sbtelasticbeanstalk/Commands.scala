package com.blendlabsinc.sbtelasticbeanstalk

import com.amazonaws.services.elasticbeanstalk.model._
import com.blendlabsinc.sbtelasticbeanstalk.{ ElasticBeanstalkKeys => eb }
import com.blendlabsinc.sbtelasticbeanstalk.core.{ AWS, Deployer, SourceBundleUploader }
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.play2war.plugin.Play2WarKeys
import sbt.Keys.streams
import sbt.Path._
import sbt.IO
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

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

  val ebDescribeEnvironmentsTask = (eb.ebDeployments, eb.ebRegion, streams) map { (ebDeployments, ebRegion, s) =>
    val ebClient = AWS.elasticBeanstalkClient(ebRegion)
    val environmentsByAppName = ebDeployments.groupBy(_.appName).mapValues(ds => ds.map(_.environmentName))
    environmentsByAppName.flatMap { case (appName, envNames) =>
      ebClient.describeEnvironments(
        new DescribeEnvironmentsRequest()
          .withApplicationName(appName)
          .withEnvironmentNames(envNames)
      ).getEnvironments
    }.toList
  }

  val ebWaitForEnvironmentsTask = (eb.ebDeployments, eb.ebRegion, streams) map { (ebDeployments, ebRegion, s) =>
    val ebClient = AWS.elasticBeanstalkClient(ebRegion)
    for (deployment <- ebDeployments) {
      val startTime = System.currentTimeMillis
      var logged = false
      var done = false
      while (!done) {
        val envDesc = ebClient.describeEnvironments(
          new DescribeEnvironmentsRequest()
            .withApplicationName(deployment.appName)
            .withEnvironmentNames(List(deployment.environmentName))
        ).getEnvironments.head
        done = (EnvironmentStatus.valueOf(envDesc.getStatus) == EnvironmentStatus.Ready &&
                EnvironmentHealth.valueOf(envDesc.getHealth) == EnvironmentHealth.Green)
        if (done) {
          if (logged) println("\n")
        } else {
          if (!logged) {
            s.log.info("Waiting for  app '" + deployment.appName + "' " +
                       "environment '" + deployment.environmentName + "' to become Ready and Green...")
            logged = true
          }
          print("\rApp: " + envDesc.getApplicationName + "   " +
                "Env: " + envDesc.getEnvironmentName + "   " +
                "Status: " + envDesc.getStatus + "   " +
                "Health: " + envDesc.getHealth + "   " +
                "(" + ((System.currentTimeMillis - startTime)/1000) + "s)")
          java.lang.Thread.sleep(4000)
        }
      }
    }
    s.log.info("All environments are Ready and Green.")
  }

  val ebConfigPullTask = (eb.ebDeployments, eb.ebRegion, eb.ebDescribeEnvironments, eb.ebConfigDirectory, streams) map {
    (ebDeployments, ebRegion, environments, ebConfigDirectory, s) => {
      val ebClient = AWS.elasticBeanstalkClient(ebRegion)
      environments.flatMap { env =>
        ebClient.describeConfigurationSettings(
          new DescribeConfigurationSettingsRequest()
            .withApplicationName(env.getApplicationName)
            .withEnvironmentName(env.getEnvironmentName)
        ).getConfigurationSettings.map { (configDesc: ConfigurationSettingsDescription) =>
          val file = ebConfigDirectory / configDesc.getApplicationName / (Option(configDesc.getTemplateName) match {
            case Some(templateName) =>
              templateName + ".template.config"
            case None =>
              configDesc.getEnvironmentName + ".env.config"
          })
          val opts = configDesc.getOptionSettings.groupBy(_.getNamespace).mapValues {
            os => os.map(o => (o.getOptionName -> o.getValue)).toMap.asJava
          }.asJava
          IO.write(file, optionSettingsToJson(opts), IO.utf8, false)
          file
        }
      }
    }.toList
  }

  private val jsonMapper: ObjectMapper = {
    import com.fasterxml.jackson.databind.SerializationFeature
    import com.fasterxml.jackson.core.JsonParser
    val m = new ObjectMapper()
    m.enable(SerializationFeature.INDENT_OUTPUT)
    m.configure(JsonParser.Feature.ALLOW_COMMENTS, true)
    m
  }
  private def optionSettingsToJson(opts: java.util.Map[String,java.util.Map[String,String]]): String = {
    jsonMapper.writeValueAsString(opts)
  }
}
