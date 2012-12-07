package com.blendlabsinc.sbtelasticbeanstalk

import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient
import com.amazonaws.services.elasticbeanstalk.model._
import com.blendlabsinc.sbtelasticbeanstalk.{ ElasticBeanstalkKeys => eb }
import com.blendlabsinc.sbtelasticbeanstalk.core.{ AWS, SourceBundleUploader }
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.play2war.plugin.Play2WarKeys
import java.io.File
import sbt.Keys.{ state, streams }
import sbt.Path._
import sbt.{ IO, Project }
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

trait ElasticBeanstalkCommands {
  val ebDeployTask = (eb.ebWait, eb.ebSetUpEnvForAppVersion, eb.ebClient, eb.ebParentEnvironments, state, streams) map {
    (waited, setUpEnvs, ebClient, parentEnvs, state, s) => {
      java.lang.Thread.sleep(15000)
      Project.runTask(eb.ebWait, state)
      setUpEnvs.map { case (deployment, setUpEnv) =>
          // Swap and terminate the parent environment if it exists.
          parentEnvs(deployment).map { parentEnv =>
            s.log.info("Swapping environment CNAMEs for app " + deployment.appName + ", " +
              "with source " + parentEnv.getEnvironmentName + " and destination " +
              setUpEnv.getEnvironmentName + ".")
            ebClient.swapEnvironmentCNAMEs(
              new SwapEnvironmentCNAMEsRequest()
                .withSourceEnvironmentName(parentEnv.getEnvironmentName)
                .withDestinationEnvironmentName(setUpEnv.getEnvironmentName)
            )
            s.log.info("Swap complete.")
            s.log.info("Waiting for DNS TTL (60 seconds) until old environment is terminated...")
            java.lang.Thread.sleep(60 * 1000)
            ebClient.terminateEnvironment(
              new TerminateEnvironmentRequest()
                .withEnvironmentName(parentEnv.getEnvironmentName)
                .withTerminateResources(true)
            )
            s.log.info("Old environment terminated.")
          }
      }
      s.log.info("Deployment complete.")
      ()
    }
  }

  val ebSetUpEnvForAppVersionTask = (eb.ebUploadSourceBundle, eb.ebParentEnvironments, eb.ebTargetEnvironments, eb.ebLocalConfigChanges, eb.ebClient, streams) map {
    (sourceBundle, parentEnvs, targetEnvs, ebLocalConfigChanges, ebClient, s) => {
      val versionLabel = sourceBundle.getS3Key
      val appVersions = targetEnvs.keys.map(_.appName).toSet.map { (appName: String) =>
        appName ->
        ebClient.createApplicationVersion(
          new CreateApplicationVersionRequest()
            .withApplicationName(appName)
            .withVersionLabel(versionLabel)
            .withSourceBundle(sourceBundle)
            .withDescription("Deployed by " + System.getenv("USER"))
        ).getApplicationVersion
      }.toMap

      parentEnvs.map { case (deployment, parentEnv) =>
          val appVersion = appVersions(deployment.appName)
          val localConfigChanges = ebLocalConfigChanges.get(deployment).getOrElse {
            s.log.warn("No local configuration found for " +
              deployment.appName + "/"+ deployment.envBaseName + ".")
            ConfigurationChanges()
          }
          val targetEnv = targetEnvs(deployment)

          val envVarSettings = deployment.environmentVariables.map { case (k, v) =>
              new ConfigurationOptionSetting("aws:elasticbeanstalk:application:environment", k, v)
          }

          s.log.info(
            "Creating new environment for application version on Elastic Beanstalk:\n" +
              "  EB app version label: " + versionLabel + "\n" +
              "  EB app: " + deployment.appName + "\n" +
              "  EB environment name: " + targetEnv.getEnvironmentName + "\n"
          )

          val res = throttled { ebClient.createEnvironment(
            new CreateEnvironmentRequest()
              .withApplicationName(targetEnv.getApplicationName)
              .withEnvironmentName(targetEnv.getEnvironmentName)
              .withSolutionStackName(targetEnv.getSolutionStackName)
              .withVersionLabel(appVersion.getVersionLabel)
              .withCNAMEPrefix(targetEnv.getCNAME)
              .withOptionSettings(envVarSettings ++ localConfigChanges.optionsToSetOnNewEnvironment.filter(_.getValue != null).filterNot(o => o.getNamespace == "aws:cloudformation:template:parameter" && o.getOptionName == "AppSource")) // TODO: get error 'Specify the subnets for the VPC' if null values are not filtered out...why? + AppSource error
          )}
          s.log.info("Elastic Beanstalk app version update complete. The new version will not be available " +
            "until the new environment is ready. When the new environment is ready, its " +
            "CNAME will be swapped with the current environment's CNAME, resulting in no downtime.\n" +
            "URL: http://" + res.getCNAME() + "\n" +
            "Status: " + res.getHealth())
          deployment -> (new EnvironmentDescription().withEnvironmentName(res.getEnvironmentName).withCNAME(res.getCNAME))
      }.toMap
    }
  }

  val ebExistingEnvironmentsTask = (eb.ebDeployments, eb.ebClient, streams) map { (ebDeployments, ebClient, s) =>
    val environmentsByAppName = ebDeployments.groupBy(_.appName).mapValues(ds => ds.map(_.envBaseName))
    environmentsByAppName.flatMap { case (appName, envBaseNames) =>
      throttled { ebClient.describeEnvironments(
        new DescribeEnvironmentsRequest()
          .withApplicationName(appName)
      ).getEnvironments.filter { e =>
        envBaseNames.count(e.getEnvironmentName.startsWith(_)) > 0
      }.filter { e =>
        EnvironmentStatus.valueOf(e.getStatus) == EnvironmentStatus.Ready
      }}
    }.toList
  }

  val ageToTerminate = 1000*60*45 // msec (45 minutes)
  val ebCleanEnvironmentsTask = (eb.ebDeployments, eb.ebExistingEnvironments, eb.ebClient, streams) map {
    (deployments, existingEnvs, ebClient, s) => {
      val envsToClean = deployments.flatMap { d =>
        existingEnvs.filter { e =>
          def trimName(cname: String) = cname.replace(".elasticbeanstalk.com", "")
          // The trailing dash means that this is NOT the currently active environment for the CNAME.
          val isThisDeployment = e.getEnvironmentName.startsWith(d.envBaseName)
          val isNotActiveCNAME = e.getCNAME != d.cname
          val ageMsec = System.currentTimeMillis - e.getDateUpdated.getTime
          isThisDeployment && isNotActiveCNAME && (ageMsec > ageToTerminate)
        }
      }
      s.log.info("Going to terminate the following environments: \n\t" + envsToClean.mkString("\n\t"))
      if (envsToClean.isEmpty) s.log.info("  (no environments found eligible for cleaning/termination)")
      if (System.getProperty("sbt.elasticbeanstalk.dryrun") != "false") {
        throw new Exception("`eb-clean` does not actually terminate environments unless you specify " +
                            "the following option: -Dsbt.elasticbeanstalk.dryrun=false.")
      }
      envsToClean.foreach { env =>
        throttled { ebClient.terminateEnvironment(
          new TerminateEnvironmentRequest()
          .withEnvironmentName(env.getEnvironmentName)
          .withTerminateResources(true)
        )}
        s.log.info("Terminated environment " + env.getEnvironmentName + ".")
      }
    }
  }

  val ebWaitForEnvironmentsTask = (eb.ebTargetEnvironments, eb.ebClient, streams) map { (targetEnvs, ebClient, s) =>
    targetEnvs.foreach { case (deployment, targetEnv) =>
      val startTime = System.currentTimeMillis
      var logged = false
      var done = false
      while (!done) {
        val elapsedSec = (System.currentTimeMillis - startTime)/1000
        val envDesc = throttled { ebClient.describeEnvironments(
          new DescribeEnvironmentsRequest()
            .withApplicationName(deployment.appName)
            .withEnvironmentNames(List(targetEnv.getEnvironmentName))
        )}.getEnvironments.headOption
        envDesc match {
          case Some(envDesc) => {
            done = (EnvironmentStatus.valueOf(envDesc.getStatus) == EnvironmentStatus.Ready &&
                    EnvironmentHealth.valueOf(envDesc.getHealth) == EnvironmentHealth.Green)
            if (done) {
              if (logged) println("\n")
            } else {
              if (!logged) {
                s.log.info("Waiting for  app '" + deployment.appName + "' " +
                           "environment '" + targetEnv.getEnvironmentName + "' to become Ready and Green...")
                logged = true
              }
              print("\rApp: " + envDesc.getApplicationName + "   " +
                    "Env: " + targetEnv.getEnvironmentName + "   " +
                    "Status: " + envDesc.getStatus + "   " +
                    "Health: " + envDesc.getHealth + "   " +
                    "(" + elapsedSec + "s)")
              java.lang.Thread.sleep(15000)
            }
          }
          case None => {
            s.log.warn("Environment " + deployment.appName + "/" + targetEnv.getEnvironmentName + " " +
                       "not found. Trying again after a delay...")
            java.lang.Thread.sleep(15000)
          }
        }
        if (elapsedSec > (20*60)) { // 20 minutes
          throw new Exception("Waited 20 minutes for " +
                              deployment.appName + "/" + targetEnv.getEnvironmentName +
                              ", still not Ready & Green. Failing.")
        }
      }
    }
    s.log.info("All environments are Ready and Green.")
  }

  val ebParentEnvironmentsTask = (eb.ebDeployments, eb.ebExistingEnvironments, eb.ebClient, streams) map {
    (ebDeployments, existingEnvs, ebClient, s) => {
      ebDeployments.map { d =>
        d -> existingEnvs.find(ee => ee.getCNAME == d.cname.toLowerCase)
      }.toMap
    }
  }

  val ebTargetEnvironmentsTask = (eb.ebParentEnvironments, eb.ebEnvironmentNameSuffix, streams) map {
    (parentEnvs, envBaseNameSuffixFn, s) => {
      parentEnvs.map { case (deployment, parentEnvOpt) =>
          deployment -> {
            val newEnvName = envBaseNameSuffixFn(deployment.envBaseName)
            new EnvironmentDescription()
              .withApplicationName(deployment.appName)
              .withEnvironmentName(newEnvName)
              .withSolutionStackName("64bit Amazon Linux running Tomcat 7")
              .withCNAME(parentEnvOpt match {
                case Some(p) => newEnvName
                case None => {
                  s.log.warn("Deployment is using CreateNewEnvironmentAndSwap scheme and environment " +
                    "does not yet exist, so the environment will be created. There is no guarantee that " +
                    "the requested CNAME '" + deployment.cname + "' will be available, so you MUST " +
                    "check the CNAME that actually gets assigned and update your sbt Deployment " +
                    "definition to use that CNAME.")
                  deployment.cname.replace(".elasticbeanstalk.com", "")
                }
              }
            )
          }
      }.toMap
    }
  }

  val ebUploadSourceBundleTask = (Play2WarKeys.war, eb.ebS3BucketName, eb.ebClient, eb.ebRequireJava6, streams) map {
    (war, s3BucketName, ebClient, ebRequireJava6, s) => {
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
      bundleLocation
    }
  }

  private def getEnvironmentConfigurationSettingsDescription(ebClient: AWSElasticBeanstalkClient, env: EnvironmentDescription): List[ConfigurationSettingsDescription] =
    throttled { ebClient.describeConfigurationSettings(
      new DescribeConfigurationSettingsRequest()
      .withApplicationName(env.getApplicationName)
      .withEnvironmentName(env.getEnvironmentName)
    )}.getConfigurationSettings.toList

  /**
   * Filter out deployments that don't exist on EB already, since they have no config to pull anyway.
   */
  private def existingParentEnvs(parentEnvs: Map[Deployment,Option[EnvironmentDescription]]): Map[Deployment,EnvironmentDescription] =
    parentEnvs.flatMap { case (d, envOpt) =>
      envOpt.map(eo => Some(d -> eo)).getOrElse(None)
    }.toMap

  val ebConfigPullTask = (eb.ebClient, eb.ebConfigDirectory, streams) map {
    (ebClient, ebConfigDirectory, s) => {
      s.log.info("Config pull: describing all applications")
      for (app <- ebClient.describeApplications().getApplications;
           templateName <- app.getConfigurationTemplates) yield {
        val appName = app.getApplicationName
        s.log.info("Config pull: describing template '" + templateName + "' for app '" + appName + "'.")
        val configDesc = ebClient.describeConfigurationSettings(
          new DescribeConfigurationSettingsRequest(appName).withTemplateName(templateName)
        ).getConfigurationSettings.head
        assert(configDesc.getTemplateName == templateName)

        // Get the configuration options so we only write settings that are different from the defaults.
        val configOpts = ebClient.describeConfigurationOptions(
          new DescribeConfigurationOptionsRequest().withApplicationName(appName).withTemplateName(templateName)
        ).getOptions

        val userSettings = configDesc.getOptionSettings.filter { setting =>
          val opt = configOpts.find(o => o.getNamespace == setting.getNamespace && o.getName == setting.getOptionName).get
          opt.getDefaultValue != setting.getValue
        }

        val baseName = templateName + ".tmpl.conf"
        val file = ebConfigDirectory / appName / baseName
        val opts = userSettings.groupBy(_.getNamespace).mapValues {
          os => os.map(o => (o.getOptionName -> o.getValue)).toMap.asJava
        }.asJava
        IO.write(file, optionSettingsToJson(opts), IO.utf8, false)
        file
      }
    }.toList
  }

  val ebConfigPushTask = (eb.ebLocalConfigChanges, eb.ebLocalConfigValidate, eb.ebClient, state, streams) map {
    (localConfigChanges, validatedLocalConfigs, ebClient, state, s) => {
      localConfigChanges.foreach { case (deployment, changes) =>
        if (!changes.optionsToSetOnNewEnvironment.isEmpty) {
          throw new Exception("Creating a new environment is not yet implemented")
        } else if (!changes.optionsToSet.isEmpty || !changes.optionsToRemove.isEmpty) {
          s.log.info("Updating config for app " + deployment.appName +
                     " environment " + deployment.envBaseName + "\n" +
                     " * Setting options: \n\t" + changes.optionsToSet.mkString("\n\t") + "\n" +
                     " * Removing options: \n\t" + changes.optionsToRemove.mkString("\n\t"))
          throw new Exception("eb-config-push CreateNewEnvironmentAndSwap not yet implemented")
          // s.log.info("Updated config for app " + deployment.appName + " environment " + deployment.envBaseName)
        } else {
          s.log.info("No local config changes for " + deployment.appName + " environment " + deployment.envBaseName)
          None
        }
      }
    }
  }

  val ebLocalConfigChangesTask = (eb.ebLocalConfig, eb.ebParentEnvironments, eb.ebClient, streams) map {
    (ebLocalConfigs, parentEnvs, ebClient, s) => {
      ebLocalConfigs.map { case (deployment, localOptionSettings) =>
        val remoteEnvOpt = parentEnvs(deployment)
        remoteEnvOpt match {
          case Some(envDesc) => {
            val remoteOptionSettings: Set[ConfigurationOptionSetting] = throttled { ebClient.describeConfigurationSettings(
              new DescribeConfigurationSettingsRequest()
              .withApplicationName(envDesc.getApplicationName)
              .withEnvironmentName(envDesc.getEnvironmentName)
            )}.getConfigurationSettings.flatMap(_.getOptionSettings).toSet
            val locallyAddedSettings = (localOptionSettings -- remoteOptionSettings)
            val locallyRemovedSettings = remoteOptionSettings.filterNot { ro => // filter out options that exist locally
              localOptionSettings.find(lo => lo.getNamespace == ro.getNamespace && lo.getOptionName == ro.getOptionName).isDefined
            }.map { o =>
              new OptionSpecification().withNamespace(o.getNamespace).withOptionName(o.getOptionName)
            }
            deployment -> ConfigurationChanges(locallyAddedSettings, locallyRemovedSettings)
          }
          case None => {
            deployment -> ConfigurationChanges(optionsToSetOnNewEnvironment = localOptionSettings.toSet)
          }
        }
      }
    }
  }

  val ebLocalConfigReadTask = (eb.ebDeployments, eb.ebClient, eb.ebConfigDirectory, streams) map {
    (ebDeployments, ebClient, ebConfigDirectory, s) => {
      ebDeployments.flatMap { deployment =>
        val envConfig = ebConfigDirectory / deployment.appName / (deployment.envBaseName + ".env.config")
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

  val ebLocalConfigValidateTask = (eb.ebLocalConfigChanges, eb.ebClient, streams) map {
    (ebLocalConfigChanges, ebClient, s) => {
      var validationFailed = false
      val validatedChanges = ebLocalConfigChanges.map { case (deployment, configChanges) =>
        deployment -> {
          val validationMessages = throttled { ebClient.validateConfigurationSettings(
            new ValidateConfigurationSettingsRequest()
              .withApplicationName(deployment.appName)
              .withEnvironmentName(deployment.envBaseName)
              .withOptionSettings(configChanges.optionsToSet)
          )}.getMessages
          validationMessages.foreach { msg =>
            val logFn = ValidationSeverity.valueOf(Map("error"->"Error", "warning"->"Warning")(msg.getSeverity)) match {
              case ValidationSeverity.Error => {
                validationFailed = true
                s.log.error (_: String)
              }
              case ValidationSeverity.Warning => s.log.warn (_: String)
            }
            logFn("For deployment " + deployment.appName + "/" + deployment.envBaseName + ": " +
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

  def throttled[T](block: => T): T = synchronized {
    java.lang.Thread.sleep(1500)
    block
  }
}
