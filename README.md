sbt-elasticbeanstalk
====================

[![Views in the last 24 hours](https://sourcegraph.com/api/repos/github.com/sqs/sbt-elasticbeanstalk/counters/views-24h.png)](https://sourcegraph.com/github.com/sqs/sbt-elasticbeanstalk)

This sbt plugin integrates with [play2-war-plugin][play2war] to let you easily deploy Play2 WAR apps to [Amazon Elastic Beanstalk][awseb].

Once configured, running `sbt eb-deploy` uploads your application's WAR file to S3 and deploys it to your Elastic Beanstalk environment.

A sample using Play 2.1-RC1 is included.


Configuration
-------------

In `project/plugins.sbt`, add:

```scala
resolvers += "Play2war plugins release" at "http://repository-play-war.forge.cloudbees.com/release/"

addSbtPlugin("com.github.play2war" % "play2-war-plugin" % "0.9-RC1")

resolvers += Resolver.url("SQS Ivy", url("http://sqs.github.com/repo"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.blendlabsinc" % "sbt-elasticbeanstalk-plugin" % "0.0.6-SNAPSHOT")
```

(Note: You need the 0.9-SNAPSHOT build of [play2-war-plugin][play2war], which supports Play 2.1 and is built from git master. For convenience, is hosted on the SQS Ivy repository included above.)

In `project/Build.scala`, add the following at the top of the file:

```scala
import com.blendlabsinc.sbtelasticbeanstalk.{ ElasticBeanstalk, Deployment }
import com.blendlabsinc.sbtelasticbeanstalk.ElasticBeanstalkKeys._
import com.github.play2war.plugin._
```

Add the following settings to your project:

```scala
val main = play.Project(appName, appVersion, appDependencies).settings(
  resolvers += Resolver.url("SQS Ivy", url("http://sqs.github.com/repo"))(Resolver.ivyStylePatterns),
  Play2WarKeys.servletVersion := "3.0",
  ebS3BucketName := "some-bucket-name",
  ebDeployments := Seq(
    Deployment(
      appName = "some-app-name",
      envBaseName = "some-environment-name",
      templateName = "my-template",
      cname = "my-cname",
      environmentVariables = Map("MyFavoriteColor" -> "blue")
    )
  ),
  ebRegion := "us-west-1"
)
  .settings(Play2WarPlugin.play2WarSettings: _*)
  .settings(ElasticBeanstalk.elasticBeanstalkSettings: _*)
```

You must create the S3 bucket and Elastic Beanstalk app and environment specified in this file. You can specify your preferred AWS region.

Create a file in `$HOME/.aws-credentials` with your AWS credentials in the following format:

```
accessKey = <your AWS access key>
secretKey = <your AWS secret key>
```


Usage
-----

Once you've configured sbt-elasticbeanstalk as described above, run the sbt `eb-deploy` task. This will:

1. Create a WAR file for your application (using [play2-war-plugin][play2war]);
2. Upload the WAR to S3 in the bucket you specified; and
3. Update your Elastic Beanstalk environment to use the new WAR.


Features
--------

**Configuration pull**: Run `eb-config-pull` at the sbt console to download environment configuration into `eb-deploy/<app>/<env>.env.conf` and `eb-deploy/<app><tmpl>.tmpl.conf` files.

**Quick update**: Run `eb-quick-update` at the sbt console to perform a quick update (an in-place swap of the WAR and a Tomcat7 restart).

**Configuration push**: Run `eb-config-push` at the sbt console to push local environment configurations to Elastic Beanstalk.

**Wait until deployed**: Run `eb-wait` to wait until all project environments are deployed (i.e., Ready status and Green health).

**Cleaning**: Run `eb-clean` to terminate old, inactive environments; run `eb-clean-app-versions` to delete old and, unused app versions.

**.ebextensions**: [Play2-war-plugin][play2war]'s `webappResource` sbt key is set to `war` by default, so any directories or files in the `war/` directory will be added to the generated WAR file. The included Play2 sample app has an example `war/.ebextensions` directory that sets some Elastic Beanstalk configuration settings.

**Environment variables**: You can specify an `environmentVariables` map in each `Deployment` that gets passed to your EB environments as environment variables.

**API actions**: Tasks prefixed with `eb-api-` map directly to the corresponding EB API commands. Try `eb-api-describe-applications`, `eb-api-describe-environments`, and `eb-api-restart-app-server <env>`.


Changelog
---------

* 0.0.6: Better, template-aware `eb-config-push` and `eb-config-pull`; added `eb-quick-update`
* 0.0.5: Add configuration pushing `eb-config-push` and validation `eb-local-config-validate`.
* 0.0.4: Add configuration pulling `eb-config-pull`; add `eb-wait` to wait until deployed; add `eb-api-describe-applications`, `eb-api-describe-environments`, and `eb-api-restart-app-server` tasks.
* 0.0.3: Can set environment variables for each deployment; added Java6 check
* 0.0.2: Added support for deployment to multiple apps and environments
* 0.0.1: Initial release


License
-------

This code is open source software licensed under the [Apache 2.0 License][apache2].

[apache2]: http://www.apache.org/licenses/LICENSE-2.0.html
[awseb]: http://aws.amazon.com/elasticbeanstalk/
[play2war]: https://github.com/dlecan/play2-war-plugin
