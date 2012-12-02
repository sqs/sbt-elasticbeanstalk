package com.blendlabsinc.sbtelasticbeanstalk

import com.amazonaws.services.elasticbeanstalk.model._
import com.blendlabsinc.sbtelasticbeanstalk.core.AWS
import com.blendlabsinc.sbtelasticbeanstalk.{ ElasticBeanstalkKeys => eb }
import sbt.{ Command, Project }
import sbt.Keys.streams
import scala.collection.JavaConversions._

trait ElasticBeanstalkAPICommands {
  val ebApiDescribeApplicationsTask = (eb.ebRegion, streams) map { (ebRegion, s) =>
    AWS.elasticBeanstalkClient(ebRegion).describeApplications().getApplications.map { app =>
      s.log.info(
        "Application name: " + app.getApplicationName + "\n" +
        (if (app.getDescription != null && !app.getDescription.isEmpty) {
          "Description: " + app.getDescription + "\n" } else "") +
        "Date created: " + app.getDateCreated.toString + "\n" +
        "Date updated: " + app.getDateUpdated.toString + "\n" +
        "Configuration templates: " +
          (if (!app.getConfigurationTemplates.isEmpty) { app.getConfigurationTemplates.mkString(", ") } else "(none)") + "\n" +
        "Last 3 versions: " +
          (if (!app.getVersions.isEmpty) { app.getVersions.take(3).mkString(", ") } else "(none)") + "\n-----"
      )
      app
    }.toList
  }

  val ebApiDescribeEnvironmentsTask = (eb.ebRegion, streams) map { (ebRegion, s) =>
    AWS.elasticBeanstalkClient(ebRegion).describeEnvironments().getEnvironments.map { env =>
      s.log.info(
        "Environment name: " + env.getEnvironmentName + "\n" +
        "Environment ID: " + env.getEnvironmentId + "\n" +
        (if (env.getDescription != null && !env.getDescription.isEmpty) {
          "Description: " + env.getDescription + "\n" } else "") +
        "Configuration template: " + Option(env.getTemplateName).getOrElse("(none)") + "\n" +
        "Date: created " + env.getDateCreated.toString + ", updated " + env.getDateUpdated.toString + "\n" +
        "Status: " + env.getStatus + "\n" +
        "Health: " + env.getHealth + "\n" +
        "Deployed version: " + env.getVersionLabel + "\n" +
        "CNAME: " + env.getCNAME + "\n" +
        "Endpoint URL: " + env.getEndpointURL + "\n-----"
      )
      env
    }.toList
  }

  val ebApiRestartAppServer = Command.single("eb-api-restart-app-server") { (state, envName) =>
    val extracted = Project.extract(state)
    val region = extracted.get(eb.ebRegion)
    state.log.info("Restarting app server for environment named '" + envName + "'.")
    AWS.elasticBeanstalkClient(region).restartAppServer(
      new RestartAppServerRequest().withEnvironmentName(envName)
    )
    state.log.info("Initiated app server restart for environment named '" + envName + "'.")
    state
  }
}
