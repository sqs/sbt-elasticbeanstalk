package com.blendlabsinc.sbtelasticbeanstalk

import com.amazonaws.services.ec2.{ model => ec2 }
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClient
import com.amazonaws.services.elasticbeanstalk.model._
import com.blendlabsinc.sbtelasticbeanstalk.{ ElasticBeanstalkKeys => eb }
import com.blendlabsinc.sbtelasticbeanstalk.core.{ AWS, SourceBundleUploader }
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import sbt.Keys.{ state, streams }
import sbt.Path._
import sbt.{ IO, Project, TaskKey, inputTask }
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._

trait ElasticBeanstalkCommands {
  val ebDeployTask = (eb.ebSetUpEnvForAppVersion, eb.ebClient, eb.ebParentEnvironments, state, streams) map {
    (setUpEnvs, ebClient, parentEnvs, state, s) => {
      sleepForApproximately(15000)
      waitForEnvironments(setUpEnvs, ebClient, s)
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
            s.log.info("Waiting for DNS TTL (60 seconds) plus 10 seconds until old environment '" + parentEnv.getEnvironmentName + "' is terminated...")
            sleepForApproximately(70 * 1000)
            s.log.info("Terminating environment '" + parentEnv.getEnvironmentName + "'.")
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

  val ebSetUpEnvForAppVersionTask = (eb.ebDeployments, eb.ebUploadSourceBundle, eb.ebTargetEnvironments, eb.ebClient, streams) map {
    (deployments, sourceBundle, targetEnvs, ebClient, s) => {
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

      targetEnvs.map { case (deployment, targetEnv) =>
          val appVersion = appVersions(deployment.appName)
          // TODO: check if remote config template is the same as the local one and warn/fail if not

          val envVarSettings = deployment.environmentVariables.map { case (k, v) =>
              new ConfigurationOptionSetting("aws:elasticbeanstalk:application:environment", k, v)
          }

          s.log.info(
            "Creating new environment for application version on Elastic Beanstalk:\n" +
              "  EB app version label: " + versionLabel + "\n" +
              "  EB app: " + deployment.appName + "\n" +
              "  EB environment name: " + targetEnv.getEnvironmentName + "\n" +
              "  CNAME: " + targetEnv.getCNAME + "\n" +
              "  Config template: " + deployment.templateName + "\n" +
              "  Environment vars: " + deployment.environmentVariables.toString
          )

          val res = ebClient.createEnvironment(
            new CreateEnvironmentRequest()
              .withApplicationName(targetEnv.getApplicationName)
              .withEnvironmentName(targetEnv.getEnvironmentName)
              .withVersionLabel(appVersion.getVersionLabel)
              .withCNAMEPrefix(targetEnv.getCNAME)
              .withTemplateName(deployment.templateName)
              .withOptionSettings(envVarSettings)
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

  // TODO: quick update only works for WARs with Tomcat
  val ebQuickUpdateTask = inputTask { (argTask: TaskKey[Seq[String]]) =>
    (argTask, eb.ebDeployments, eb.ebParentEnvironments, eb.ebUploadSourceBundle, eb.ebClient, eb.ec2Client, eb.ebRegion, streams) map {
      (args: Seq[String], deployments, parentEnvs, sourceBundle, ebClient, ec2Client, awsRegion, s) => {
        val appFilter = if (args.length == 1) {
          s.log.info("Only quick-updating apps containing '" + args(0) + "'.")
          args(0)
        } else if (args.length > 1) {
          throw new Exception("eb-quick-update only accepts one space-delimited argument; provided " + args.length)
        } else ""
        deployments.foreach { d =>
          if (!d.appName.contains(appFilter)) {
            s.log.warn("Quick update: skipping deployment for app '" + d.appName + "' because it does not match filter '" + appFilter + "'.")
          } else if (!parentEnvs.contains(d)) {
            s.log.warn("Quick update: Can't update deployment " + d.toString + " because it has no running environment.")
          } else {
            val parentEnv = parentEnvs(d)
            val logPrefix = "Quick update [" + d.appName + ":" + d.envBaseName + "]: "

            s.log.info(logPrefix + "Describing environment resources for environment '" + parentEnv.getEnvironmentName + "'.")
            val instanceIds = ebClient.describeEnvironmentResources(
              new DescribeEnvironmentResourcesRequest().withEnvironmentName(parentEnv.getEnvironmentName)
            ).getEnvironmentResources.getInstances.map(_.getId)

            s.log.info(logPrefix + "Looking up IP addresses for instances: " + instanceIds + ".")
            val instanceAddresses = ec2Client.describeInstances(
              new ec2.DescribeInstancesRequest().withInstanceIds(instanceIds.toSet)
            ).getReservations.flatMap(_.getInstances).map { i =>
              if (i.getPublicDnsName != null && i.getPublicDnsName != "") i.getPublicDnsName else i.getPrivateIpAddress
            }

            s.log.info(logPrefix + "Found IP addresses " + instanceAddresses)
            for (instanceAddress <- instanceAddresses) {
              s.log.info(logPrefix + "SSHing to " + instanceAddress + " to update " + d.envBaseName + "...")
              val warUrl = {
                val s3Client = AWS.s3Client(awsRegion)
                s3Client.generatePresignedUrl(sourceBundle.getS3Bucket, sourceBundle.getS3Key, new java.util.Date(System.currentTimeMillis + 1000*60*60*24)).toString
              }
              execRemote(instanceAddress, List(
                "curl -o /tmp/latest.war '" + warUrl + "'",
                "sudo service tomcat7 stop",
                "sudo rm -rf /usr/share/tomcat7/webapps/ROOT",
                "sudo bash -c 'cd /usr/share/tomcat7/webapps && mkdir ROOT && cd ROOT && unzip /tmp/latest.war && chown -R tomcat:tomcat /usr/share/tomcat7/webapps'",
                "sudo service tomcat7 start"
              ))
            }
            s.log.info(logPrefix + "Finished")
          }
        }
      }
    }
  }

  def execRemote(host: String, commands: List[String]) {
    import java.io.{ File, IOException }
    import java.security.PublicKey
    import java.util.concurrent.TimeUnit
    import net.schmizz.sshj.SSHClient
    import net.schmizz.sshj.common.IOUtils
    import net.schmizz.sshj.connection.channel.direct.Session
    import net.schmizz.sshj.connection.channel.direct.Session.Command
    import net.schmizz.sshj.transport.verification.HostKeyVerifier
    import scala.io.Source

    val keyFile = new File(System.getProperty("user.home"), ".ssh/blendlive.pem").getAbsolutePath

    val ssh = new SSHClient()
    ssh.addHostKeyVerifier(new HostKeyVerifier() {
      def verify(arg0: String, arg1: Int, arg2: PublicKey): Boolean = true
    })

    ssh.connect(host)
    try {
      ssh.authPublickey("ec2-user", keyFile)
      for (command <- commands) {
        val session = ssh.startSession()
        try {
          session.allocateDefaultPTY()
          println("Executing command on remote host " + host + ": " + command)
          val cmd = session.exec(command)
          cmd.join(60, TimeUnit.SECONDS)
          val out = Source.fromInputStream(cmd.getInputStream, "UTF-8").getLines.mkString("\n")
          println("  --> Output: " + out)
        } finally {
          session.close()
        }
      }
    } finally {
      ssh.disconnect()
    }
  }

  val ebExistingEnvironmentsTask = (eb.ebDeployments, eb.ebClient, streams) map { (deployments, ebClient, s) =>
    val existingEnvs = deployments.map(_.appName).toSet.map { (appName: String) =>
      throttled { ebClient.describeEnvironments(
        new DescribeEnvironmentsRequest()
          .withApplicationName(appName)
      )}
    }.flatMap(_.getEnvironments).toList.filter { env =>
      EnvironmentStatus.valueOf(env.getStatus) == EnvironmentStatus.Ready &&
      deployments.find(_.environmentCorrespondsToThisDeployment(env)).isDefined
    }.groupBy { env =>
      deployments.find(_.environmentCorrespondsToThisDeployment(env)).get
    }
    s.log.debug("eb-existing-environments: " + existingEnvs.toString)
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
      s.log.warn("Sleeping for 15 seconds before terminating environments...")
      java.lang.Thread.sleep(15 * 1000)
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

  val ebCleanAppVersionsTask = (eb.ebDeployments, eb.ebExistingEnvironments, eb.ebClient, streams) map {
    (deployments, existingEnvs, ebClient, s) => {
      def appVersionIsNotInUse(appName: String, versionLabel: String): Boolean = {
        existingEnvs.values.flatten.count { env =>
          env.getApplicationName == appName && env.getVersionLabel == versionLabel
        } == 0
      }
      var i = 0
      deployments.map(_.appName).foreach { appName =>
        s.log.info("Describing app versions for app '" + appName + "'.")
        val appVersions = throttled { ebClient.describeApplicationVersions(
          new DescribeApplicationVersionsRequest().withApplicationName(appName)
        )}.getApplicationVersions
        appVersions.foreach { appVersion =>
          val age = System.currentTimeMillis - appVersion.getDateUpdated.getTime
          if (appVersionIsNotInUse(appName, appVersion.getVersionLabel) && age > (1000 * 60 * 60 * 12)/*12 hours*/) {
            i += 1
            s.log.info("Deleting app version '" + appVersion.getVersionLabel + "' in app '" + appName + "'.")
            def deleteAppVersion(deleteSourceBundle: Boolean) {
              throttled { ebClient.deleteApplicationVersion(
                new DeleteApplicationVersionRequest()
                  .withApplicationName(appName)
                  .withDeleteSourceBundle(deleteSourceBundle)
                  .withVersionLabel(appVersion.getVersionLabel)
              )}
            }
            try {
              deleteAppVersion(true)
            } catch {
              case e: Exception => deleteAppVersion(false)
            }
            s.log.info("Finished deleting app version '" + appVersion.getVersionLabel + "' in app '" + appName + "'.")
          } else {
            s.log.info("Not deleting app version '" + appVersion.getVersionLabel + "' in app '" + appName + "' because it is either in use or not old enough.")
          }
        }
      }
      s.log.info("Finished deleting " + i + " old app versions.")
    }
  }

  val ebWaitForEnvironmentsTask = (eb.ebTargetEnvironments, eb.ebClient, streams) map { (targetEnvs, ebClient, s) =>
    waitForEnvironments(targetEnvs, ebClient, s)
  }

  def waitForEnvironments(envs: Map[Deployment,EnvironmentDescription], ebClient: AWSElasticBeanstalkClient, s: sbt.std.TaskStreams[_]) = {
    envs.foreach { case (deployment, targetEnv) =>
      val startTime = System.currentTimeMillis
      var logged = false
      var done = false
      while (!done) {
        val elapsedSec = (System.currentTimeMillis - startTime)/1000
        sleepForApproximately(5000)
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
              sleepForApproximately(15000)
            }
          }
          case None => {
            s.log.warn("Environment " + deployment.appName + "/" + targetEnv.getEnvironmentName + " " +
                       "not found. Trying again after a delay...")
            sleepForApproximately(15000)
          }
        }
        if (elapsedSec > (20*60)) { // 20 minutes
          throw new Exception("Waited 20 minutes for " +
                              deployment.appName + "/" + targetEnv.getEnvironmentName +
                              ", still not Ready & Green. Failing.")
        }
      }
    }
    s.log.info("All environments are Ready and Green: " + envs.keys.toString + ".")
  }

  val ebParentEnvironmentsTask = (eb.ebExistingEnvironments, eb.ebClient, streams) map {
    (existingEnvs, ebClient, s) => {
      val parentEnvs = existingEnvs.flatMap { case (deployment, envs) =>
          envs.find(_.getCNAME == deployment.cname) match {
            case Some(parentEnv) => Some(deployment -> parentEnv)
            case None => None
          }
      }.toMap
      s.log.debug("eb-parent-environments: " + parentEnvs.toString)
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
              .withSolutionStackName(deployment.solutionStackName)
              .withCNAME(parentEnvs.get(deployment) match {
                case Some(p) => newEnvName
                case None => {
                  s.log.warn("Deployment environment for " + deployment.toString + " " +
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

  val ebUploadSourceBundleTask = (eb.ebAppBundle, eb.ebS3BucketName, eb.ebClient, eb.ebRequireJava6, streams) map {
    (appBundle, s3BucketName, ebClient, ebRequireJava6, s) => {

      // Returns a File object corresponding to the .zip file of directory `dir`.
      def zipDirectory(dir: File): File = {
        import sbt.IO
        s.log.info("Zipping up directory '" + dir.getAbsolutePath + "'.")
        val tmpZipFile = new File(IO.createTemporaryDirectory, dir.getName + ".zip")
        IO.zip(IO.listFiles(dir).map { f => (f, IO.relativize(dir, f).get) }, tmpZipFile)
        s.log.info("Finished zipping up directory '" + dir.getAbsolutePath + "' to '" + tmpZipFile.getAbsolutePath + " (" + (tmpZipFile.length/1024) + " kb compressed).")
        tmpZipFile
      }

      if (ebRequireJava6 && System.getProperty("java.specification.version") != "1.6") {
        throw new Exception(
          "ebRequireJava6 := true, but you are currently running in Java " +
          System.getProperty("java.specification.version") + ". As of Dec 2012, " +
          "Elastic Beanstalk is incompatible with Java7. You should use Java6 to compile " +
          "and deploy WARs. You can also set ebRequireJava6 := false in " +
          "your sbt settings to suppress this warning, but beware that Java7-compiled WARs " +
          "currently fail in strange ways on Elastic Beanstalk. See " +
          "http://qslack.com/2012/12/using-java-1-7-on-amazon-aws-elastic-beanstalk/ for a workaround."
        )
      }

      val bundleFile = if (appBundle.isDirectory) {
        zipDirectory(appBundle)
      } else appBundle

      s.log.info("Uploading " + bundleFile.getName + " (" + (bundleFile.length/1024/1024) + " MB) " +
                 "to Amazon S3 bucket '" + s3BucketName + "'")
      val u = new SourceBundleUploader(bundleFile, s3BucketName, AWS.awsCredentials)
      val bundleLocation = u.upload()
      s.log.info("App bundle file upload complete: '" + bundleFile.getName + "'.")
      bundleLocation
    }
  }

  val ebConfigPullTask = (eb.ebDeployments, eb.ebParentEnvironments, eb.ebClient, eb.ebConfigDirectory, streams) map {
    (deployments, parentEnvs, ebClient, configDir, s) => {
      def writeSettings(appName: String, configBaseName: String, settings: Set[ConfigurationOptionSetting]): File = {
        val file = configDir / appName / configBaseName

        // Warn if an "_all" config file also exists.
        val allFile = configDir / "_all" / configBaseName
        val settingsNotInAllFile = if (allFile.exists) {
          s.log.warn(
            "Config pull: an _all config file also exists at '" + allFile + "', so we will only write " +
            "settings that are not in the _all file to '" + file + "'."
          )
          val settingsInAllFile = readConfigFile(allFile)
          settings -- settingsInAllFile
        } else settings

        if (!settingsNotInAllFile.isEmpty) {
          val settingsMap = settingsNotInAllFile.groupBy(_.getNamespace).mapValues {
            os => os.map(o => (o.getOptionName -> o.getValue)).toMap.asJava
          }.asJava

          s.log.info("Config pull: writing settings to file '" + file + "'.")
          IO.write(file, optionSettingsToJson(settingsMap), IO.utf8, false)
          file
        } else allFile
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

        val templateConfigSettings = readConfigFiles(templateFilesForDeployment(configDir, deployment))
        val configSettingsNotSpecifiedInTemplate = configSettings -- templateConfigSettings

        writeSettings(appName, deployment.envBaseName + ".env.conf", nonDefaultSettings(configSettingsNotSpecifiedInTemplate, configOpts))
      }

      templateFiles ++ envConfigFiles
    }.toList
  }

  val ebConfigPushTask = (eb.ebDeployments, eb.ebApiDescribeApplications, eb.ebConfigDirectory, eb.ebClient, streams) map {
    (deployments, allApps, configDir, ebClient, s) => {
      def remoteConfigTemplateExists(appName: String, templateName: String): Boolean = {
        allApps.find(_.getApplicationName == appName).get.getConfigurationTemplates.contains(templateName)
      }
      deployments.foreach { d =>
        val tmplFilePaths = templateFilesForDeployment(configDir, d)
        s.log.info("Config push: Using configuration template files '" + tmplFilePaths + "' for app '" + d.appName + "'.")

        def deleteConfigTemplate() {
          s.log.info("Config push: Deleting previous configuration template '" + d.templateName + "' for app '" + d.appName + "'.")
          throttled { ebClient.deleteConfigurationTemplate(
            new DeleteConfigurationTemplateRequest()
              .withApplicationName(d.appName)
              .withTemplateName(d.templateName)
          )}
          s.log.info("Config push: Finished deleting configuration template '" + d.templateName + "' for app '" + d.appName + "' at path '" + tmplFilePaths + "'.")
        }

        def createConfigTemplate() {
          s.log.info("Creating configuration template '" + d.templateName + "' for app '" + d.appName + "'.")
          throttled { ebClient.createConfigurationTemplate(
            new CreateConfigurationTemplateRequest()
              .withApplicationName(d.appName)
              .withSolutionStackName(d.solutionStackName)
              .withTemplateName(d.templateName)
              .withOptionSettings(readConfigFiles(tmplFilePaths))
          )}
          s.log.info("Config push: Finished creating configuration template '" + d.templateName + "' for app '" + d.appName + "' at path '" + tmplFilePaths + "'.")
        }

        if (remoteConfigTemplateExists(d.appName, d.templateName)) {
          deleteConfigTemplate()
        }

        try {
          createConfigTemplate()
        } catch {
          case e: Exception => {
            // Workaround for issue where configuration templates are not being returned in describeApplications,
            // and so we don't delete existing templates before recreating them.
            // Status Code: 400, AWS Service: AWSElasticBeanstalk, AWS Request ID: 1dbde02e-439f-11e2-87a8-696de970b971, AWS Error Code: InvalidParameterValue, AWS Error Message: Configuration Template play2-sample already exists.
            deleteConfigTemplate()
            createConfigTemplate()
          }
        }
      }
    }
  }

  def templateFilesForDeployment(configDir: File, deployment: Deployment): List[File] = {
    val filename = deployment.templateName + ".tmpl.conf"
    val searchPaths = Seq(
      configDir / deployment.appName / filename,
      configDir / "_all" / filename
    )
    val paths = searchPaths.filter(_.exists)
    if (paths.isEmpty) {
      throw new Exception(
        "Config push: Couldn't find configuration template file for deployment " + deployment + ".\n" +
          "Looked in: " + searchPaths.mkString(":")
      )
    }
    paths.toList
  }

  // Read and merge the specified configuration files, with earlier files taking precedence over
  // later files in the `files` list.
  def readConfigFiles(files: List[File]): Set[ConfigurationOptionSetting] = {
    val mergedSettings = collection.mutable.Map[(String, String), ConfigurationOptionSetting]()
    files.foreach { file =>
      val settings = readConfigFile(file)
      settings.foreach { setting =>
        val key = (setting.getNamespace, setting.getOptionName)
        if (!mergedSettings.contains(key)) {
          mergedSettings(key) = setting
        }
      }
    }
    mergedSettings.values.filter(_.getValue != null).toSet
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
    sleepForApproximately(1500)
    block
  }

  def sleepForApproximately(msec: Int) {
    java.lang.Thread.sleep(msec + scala.util.Random.nextInt(msec/3))
  }
}
