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
  val ebDeployTask = (eb.ebSetUpEnvForAppVersion, eb.ebClient, eb.ebParentEnvironments, state, streams) map {
    (setUpEnvs, ebClient, parentEnvs, state, s) => {
      java.lang.Thread.sleep(15000)
      Project.runTask(eb.ebWait, state)
      setUpEnvs.map { case (deployment, setUpEnv) =>
          // Swap and terminate the parent environment if it exists.
          parentEnvs.get(deployment).map { parentEnv =>
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

  val ebSetUpEnvForAppVersionTask = (eb.ebDeployments, eb.ebUploadSourceBundle, eb.ebParentEnvironments, eb.ebTargetEnvironments, eb.ebClient, streams) map {
    (deployments, sourceBundle, parentEnvs, targetEnvs, ebClient, s) => {
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
          // TODO: check if remote config template is the same as the local one and warn/fail if not
          val targetEnv = targetEnvs(deployment)

          val envVarSettings = deployment.environmentVariables.map { case (k, v) =>
              new ConfigurationOptionSetting("aws:elasticbeanstalk:application:environment", k, v)
          }

          s.log.info(
            "Creating new environment for application version on Elastic Beanstalk:\n" +
              "  EB app version label: " + versionLabel + "\n" +
              "  EB app: " + deployment.appName + "\n" +
              "  EB environment name: " + targetEnv.getEnvironmentName + "\n" +
              "  CNAME: " + targetEnv.getCNAME + "\n" +
              "  Config template: " + deployment.templateName
          )

          val res = ebClient.createEnvironment(
            new CreateEnvironmentRequest()
              .withApplicationName(targetEnv.getApplicationName)
              .withEnvironmentName(targetEnv.getEnvironmentName)
              .withVersionLabel(appVersion.getVersionLabel)
              .withCNAMEPrefix(targetEnv.getCNAME)
              .withTemplateName(deployment.templateName)
          )
          s.log.info("Elastic Beanstalk app version update complete. The new version will not be available " +
            "until the new environment is ready. When the new environment is ready, its " +
            "CNAME will be swapped with the current environment's CNAME, resulting in no downtime.\n" +
            "URL: http://" + res.getCNAME() + "\n" +
            "Status: " + res.getHealth())
          deployment -> (new EnvironmentDescription().withEnvironmentName(res.getEnvironmentName).withCNAME(res.getCNAME))
      }.toMap
    }
  }

  val ebExistingEnvironmentsTask = (eb.ebDeployments, eb.ebClient, streams) map { (deployments, ebClient, s) =>
    val existingEnvs = deployments.map(_.appName).toSet.map { (appName: String) =>
      throttled { ebClient.describeEnvironments(
        new DescribeEnvironmentsRequest()
          .withApplicationName(appName)
      )}
    }.flatMap(_.getEnvironments).toList.filter { env =>
      EnvironmentStatus.valueOf(env.getStatus) == EnvironmentStatus.Ready
    }.groupBy { env =>
      deployments.find(_.environmentCorrespondsToThisDeployment(env)).get
    }
    s.log.info("eb-existing-environments: " + existingEnvs.toString)
    existingEnvs
  }

  val ageToTerminate = 1000*60*45 // msec (45 minutes)
  val ebCleanEnvironmentsTask = (eb.ebDeployments, eb.ebExistingEnvironments, eb.ebClient, streams) map {
    (deployments, existingEnvs, ebClient, s) => {
      val envsToClean: Iterable[EnvironmentDescription] = existingEnvs.flatMap { case (deployment, envs) =>
          envs.filter { env =>
            def trimName(cname: String) = cname.replace(".elasticbeanstalk.com", "")
            val isNotActiveCNAME = (env.getCNAME != deployment.cname)
            val ageMsec = System.currentTimeMillis - env.getDateUpdated.getTime
            isNotActiveCNAME && (ageMsec > ageToTerminate)
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

  val ebParentEnvironmentsTask = (eb.ebExistingEnvironments, eb.ebClient, streams) map {
    (existingEnvs, ebClient, s) => {
      val parentEnvs = existingEnvs.flatMap { case (deployment, envs) =>
          envs.find(_.getCNAME == deployment.cname) match {
            case Some(parentEnv) => Some(deployment -> parentEnv)
            case None => None
          }
      }.toMap
      s.log.info("eb-parent-environments: " + parentEnvs.toString)
      parentEnvs
    }
  }

  val ebTargetEnvironmentsTask = (eb.ebDeployments, eb.ebParentEnvironments, eb.ebEnvironmentNameSuffix, streams) map {
    (deployments, parentEnvs, envBaseNameSuffixFn, s) => {
      deployments.map { deployment =>
          deployment -> {
            val newEnvName = envBaseNameSuffixFn(deployment.envBaseName)
            new EnvironmentDescription()
              .withApplicationName(deployment.appName)
              .withEnvironmentName(newEnvName)
              .withSolutionStackName(tomcat7SolutionStackName)
              .withCNAME(parentEnvs.get(deployment) match {
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

  val ebConfigPullTask = (eb.ebDeployments, eb.ebParentEnvironments, eb.ebClient, eb.ebConfigDirectory, streams) map {
    (deployments, parentEnvs, ebClient, configDir, s) => {
      def writeSettings(appName: String, configBaseName: String, settings: Set[ConfigurationOptionSetting]): File = {
        val file = configDir / appName / configBaseName
        val settingsMap = settings.groupBy(_.getNamespace).mapValues {
          os => os.map(o => (o.getOptionName -> o.getValue)).toMap.asJava
        }.asJava
        s.log.info("Config pull: writing settings to file '" + file + "'.")
        IO.write(file, optionSettingsToJson(settingsMap), IO.utf8, false)
        
        // Warn if an "_all" config file also exists.
        val allFile = configDir / "_all" / configBaseName
        if (allFile.exists) {
          s.log.warn(
            "Config pull: a config file also exists at '" + allFile + "'. The file at '" + file + "' " +
            "may be a duplicate. The eb-config-pull command doesn't yet check for this, so you should " +
            "check manually and remove the non-_all file if it is a duplicate, to avoid confusion."
          )
        }

        file
      }

      // * Filter out settings whose value is the default value for that setting.
      def nonDefaultSettings(settings: Set[ConfigurationOptionSetting], configOpts: Iterable[ConfigurationOptionDescription]): Set[ConfigurationOptionSetting] =
        settings.filter { setting =>
          val opt = configOpts.find(o => o.getNamespace == setting.getNamespace && o.getName == setting.getOptionName).get
          opt.getDefaultValue != setting.getValue
        }.map { setting =>
          // Filter out the CloudFormation Ref that appears when you describe configuration templates:
          // { ..., "SecurityGroups" : "default,{\"Ref\":\"AWSEBSecurityGroup\"}", ... }
          if (setting.getNamespace == "aws:autoscaling:launchconfiguration" &&
              setting.getOptionName == "SecurityGroups") {
            setting.withValue(setting.getValue.replace(",{\"Ref\":\"AWSEBSecurityGroup\"}", ""))
          } else setting
        }.toSet

      val allDefinedTemplates = deployments.groupBy(_.appName).mapValues(ds => ds.map(_.templateName).toSet)
      // Get configuration templates.
      val templateFiles = for ((appName, templateNames) <- allDefinedTemplates;
                               templateName <- templateNames) yield {
        s.log.info("Config pull: describing configuration settings for template '" + templateName + "' in app '" + appName + "'.")
        val configSettings = ebClient.describeConfigurationSettings(
          new DescribeConfigurationSettingsRequest(appName).withTemplateName(templateName)
        ).getConfigurationSettings.head.getOptionSettings.toSet
        s.log.info("Config pull: describing config options for template '" + templateName + "' in app '" + appName + "'.")
        val configOpts = throttled { ebClient.describeConfigurationOptions(
          new DescribeConfigurationOptionsRequest().withApplicationName(appName).withTemplateName(templateName)
        )}.getOptions.toSet
        writeSettings(appName, templateName + ".tmpl.conf", nonDefaultSettings(configSettings, configOpts))
      }

      // Get environment configurations.
      val envConfigFiles = for ((deployment, env) <- parentEnvs) yield {
        val appName = env.getApplicationName
        val envName = env.getEnvironmentName
        s.log.info("Config pull: describing configuration settings for env '" + envName + "' in app '" + appName + "'.")
        val configSettings = throttled { ebClient.describeConfigurationSettings(
          new DescribeConfigurationSettingsRequest(appName).withEnvironmentName(envName)
        )}.getConfigurationSettings.head.getOptionSettings.toSet
        s.log.info("Config pull: describing config options for env '" + envName + "' in app '" + appName + "'.")
        val configOpts = throttled { ebClient.describeConfigurationOptions(
          new DescribeConfigurationOptionsRequest().withEnvironmentName(envName)
        )}.getOptions.toSet

        val templateConfigSettings = readConfigFile(templateFileForDeployment(configDir, deployment))
        val configSettingsNotSpecifiedInTemplate = configSettings -- templateConfigSettings

        writeSettings(appName, deployment.envBaseName + ".env.conf", nonDefaultSettings(configSettingsNotSpecifiedInTemplate, configOpts))
      }

      templateFiles ++ envConfigFiles
    }.toList
  }

  val ebConfigPushTask = (eb.ebDeployments, eb.ebApiDescribeApplications, eb.ebConfigDirectory, eb.ebClient, streams) map {
    (deployments, allApps, configDir, ebClient, s) => {
      def configTemplateExists(appName: String, templateName: String): Boolean = {
        allApps.find(_.getApplicationName == appName).get.getConfigurationTemplates.contains(templateName)
      }
      deployments.foreach { d =>
        val tmplFilePath = templateFileForDeployment(configDir, d)
        s.log.info("Config push: Using configuration template file '" + tmplFilePath + "' for app '" + d.appName + "'.")
        if (configTemplateExists(d.appName, d.templateName)) {
          s.log.info("Config push: Updating configuration template '" + d.templateName + "' for app '" + d.appName + "'.")
          throttled { ebClient.updateConfigurationTemplate(
            new UpdateConfigurationTemplateRequest()
              .withApplicationName(d.appName)
              .withTemplateName(d.templateName)
              .withOptionSettings(readConfigFile(tmplFilePath))
          )}
          s.log.info("Config push: Finished updating configuration template '" + d.templateName + "' for app '" + d.appName + "' at path '" + tmplFilePath + "'.")
        } else {
          s.log.info("Creating configuration template '" + d.templateName + "' for app '" + d.appName + "'.")
          throttled { ebClient.createConfigurationTemplate(
            new CreateConfigurationTemplateRequest()
              .withApplicationName(d.appName)
              .withSolutionStackName(tomcat7SolutionStackName)
              .withTemplateName(d.templateName)
              .withOptionSettings(readConfigFile(tmplFilePath))
          )}
          s.log.info("Config push: Finished creating configuration template '" + d.templateName + "' for app '" + d.appName + "' at path '" + tmplFilePath + "'.")
        }
      }
    }
  }

  def templateFileForDeployment(configDir: File, deployment: Deployment): File = {
    val filename = deployment.templateName + ".tmpl.conf"
    val searchPaths = Seq(
      configDir / deployment.appName / filename,
      configDir / "_all" / filename
    )
    val filePath = searchPaths.find(_.exists).getOrElse {
      throw new Exception(
        "Config push: Couldn't find configuration template file for deployment " + deployment + ".\n" +
          "Looked in: " + searchPaths.mkString(":")
      )
    }
    filePath
  }

  def readConfigFile(file: File): Set[ConfigurationOptionSetting] = {
    val settingsMap = jsonToOptionSettingsMap(file)
    settingsMap.flatMap { case (namespace, options) =>
        options.map { case (optionName, value) =>
            new ConfigurationOptionSetting(namespace, optionName, value)
        }
    }.toSet
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

  val tomcat7SolutionStackName = "64bit Amazon Linux running Tomcat 7"
}
