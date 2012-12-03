package com.blendlabsinc.sbtelasticbeanstalk

import com.amazonaws.services.elasticbeanstalk.model._
import com.blendlabsinc.sbtelasticbeanstalk.{ ElasticBeanstalkKeys => eb }
import com.blendlabsinc.sbtelasticbeanstalk.core.{ AWS, Deployer, SourceBundleUploader }
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.play2war.plugin.Play2WarKeys
import java.io.File
import sbt.Keys.{ state, streams }
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

  val ebConfigPushTask = (eb.ebLocalConfigChanges, eb.ebLocalConfigValidate, eb.ebRegion, state, streams) map {
    (localConfigChanges, validatedLocalConfigs, ebRegion, state, s) => {
      val ebClient = AWS.elasticBeanstalkClient(ebRegion)
      localConfigChanges.flatMap { case (deployment, changes) =>
        if (!changes.optionsToSetAfterCreatingNewEnvironment.isEmpty) {
          throw new Exception("Creating a new environment is not yet implemented")
        } else if (!changes.optionsToSet.isEmpty || !changes.optionsToRemove.isEmpty) {
          s.log.info("Updating config for app " + deployment.appName +
                     " environment " + deployment.environmentName + "\n" +
                     " * Setting options: \n\t" + changes.optionsToSet.mkString("\n\t") + "\n" +
                     " * Removing options: \n\t" + changes.optionsToRemove.mkString("\n\t"))
          val res = ebClient.updateEnvironment(
            new UpdateEnvironmentRequest()
            .withEnvironmentName(deployment.environmentName)
            .withOptionSettings(changes.optionsToSet)
            .withOptionsToRemove(changes.optionsToRemove)
          )
          s.log.info("Updated config for app " + deployment.appName + " environment " + deployment.environmentName)
          Some(res)
        } else {
          s.log.info("No local config changes for " + deployment.appName + " environment " + deployment.environmentName)
          None
        }
      }.toList
    }
  }

  val ebLocalConfigChangesTask = (eb.ebLocalConfig, eb.ebDescribeEnvironments, eb.ebRegion, streams) map {
    (ebLocalConfigs, environments, ebRegion, s) => {
      val ebClient = AWS.elasticBeanstalkClient(ebRegion)
      ebLocalConfigs.map { case (deployment, localOptionSettings) =>
        val remoteEnvOpt = environments.find(
          e => e.getApplicationName == deployment.appName && e.getEnvironmentName == deployment.environmentName
        )
        remoteEnvOpt match {
          case Some(envDesc) => {
            val remoteOptionSettings: Set[ConfigurationOptionSetting] = ebClient.describeConfigurationSettings(
              new DescribeConfigurationSettingsRequest()
              .withApplicationName(envDesc.getApplicationName)
              .withEnvironmentName(envDesc.getEnvironmentName)
            ).getConfigurationSettings.flatMap(_.getOptionSettings).toSet
            val locallyAddedSettings = (localOptionSettings -- remoteOptionSettings)
            val locallyRemovedSettings = remoteOptionSettings.filterNot { ro => // filter out options that exist locally
              localOptionSettings.find(lo => lo.getNamespace == ro.getNamespace && lo.getOptionName == ro.getOptionName).isDefined
            }.map { o =>
              new OptionSpecification().withNamespace(o.getNamespace).withOptionName(o.getOptionName)
            }
            deployment -> ConfigurationChanges(locallyAddedSettings, locallyRemovedSettings)
          }
          case None => {
            deployment -> ConfigurationChanges(optionsToSetAfterCreatingNewEnvironment = localOptionSettings.toSet)
          }
        }
      }
    }
  }

  val ebLocalConfigReadTask = (eb.ebDeployments, eb.ebRegion, eb.ebConfigDirectory, streams) map {
    (ebDeployments, ebRegion, ebConfigDirectory, s) => {
      ebDeployments.flatMap { deployment =>
        val envConfig = ebConfigDirectory / deployment.appName / (deployment.environmentName + ".env.config")
        envConfig.exists match {
          case true => {
            val settingsMap = jsonToOptionSettingsMap(envConfig)
            s.log.info("Using local config for deployment " + deployment.toString +
                       " at path " + envConfig.getAbsolutePath)
            Some(
              deployment ->
              settingsMap.flatMap { case (namespace, options) =>
                options.map { case (optionName, value) =>
                  new ConfigurationOptionSetting(namespace, optionName, value)
                }
              }.toSet
            )
          }
          case false => {
            s.log.warn("No local config found for deployment " + deployment.toString +
                       " at path " + envConfig.getAbsolutePath)
            None
          }
        }
      }.toMap
    }
  }

  val ebLocalConfigValidateTask = (eb.ebLocalConfigChanges, eb.ebRegion, streams) map {
    (ebLocalConfigChanges, ebRegion, s) => {
      var validationFailed = false
      val ebClient = AWS.elasticBeanstalkClient(ebRegion)
      val validatedChanges = ebLocalConfigChanges.map { case (deployment, configChanges) =>
        deployment -> {
          val validationMessages = ebClient.validateConfigurationSettings(
            new ValidateConfigurationSettingsRequest()
              .withApplicationName(deployment.appName)
              .withEnvironmentName(deployment.environmentName)
              .withOptionSettings(configChanges.optionsToSet)
          ).getMessages
          validationMessages.foreach { msg =>
            val logFn = ValidationSeverity.valueOf(Map("error"->"Error", "warning"->"Warning")(msg.getSeverity)) match {
              case ValidationSeverity.Error => {
                validationFailed = true
                s.log.error (_: String)
              }
              case ValidationSeverity.Warning => s.log.warn (_: String)
            }
            logFn("For deployment " + deployment.appName + "/" + deployment.environmentName + ": " +
                  msg.getNamespace + ":" + msg.getOptionName + ": " +
                  msg.getMessage + " (" + msg.getSeverity + ")")
          }
          configChanges
        }
      }.toMap
      if (validationFailed) {
        throw new Exception("Local configuration failed to validate. See messages above.")        
      } else {
        validatedChanges
      }
    }
  }

  private val jsonMapper: ObjectMapper = {
    import com.fasterxml.jackson.databind.SerializationFeature
    import com.fasterxml.jackson.core.JsonParser
    val m = new ObjectMapper()
    m.enable(SerializationFeature.INDENT_OUTPUT)
    m.configure(JsonParser.Feature.ALLOW_COMMENTS, true)
    m
  }
  private def jsonToOptionSettingsMap(jsonFile: File): java.util.HashMap[String,java.util.HashMap[String,String]] = {
    jsonMapper.readValue(jsonFile, classOf[java.util.HashMap[String,java.util.HashMap[String,String]]])
  }
  private def optionSettingsToJson(opts: java.util.Map[String,java.util.Map[String,String]]): String = {
    jsonMapper.writeValueAsString(opts)
  }
}
