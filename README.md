sbt-elasticbeanstalk
====================

This module integrates with [play2-war-plugin][play2war] to let you easily deploy Play2 WAR apps to [Amazon Elastic Beanstalk][awseb]. It uploads your application's WAR file to S3 and deploys it to your Elastic Beanstalk environment.

A sample using Play 2.1-RC1 is included.


Configuration
-------------

In `project/plugins.sbt`, add:

```scala
resolvers += Resolver.url("SQS Ivy", url("https://sqs.github.com/repo"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.github.play2war" % "play2-war-plugin" % "0.9-SNAPSHOT")

addSbtPlugin("com.blendlabsinc" % "sbt-elasticsearch-plugin" % "0.0.1-SNAPSHOT")
```

(Note: You need the 0.9-SNAPSHOT build of [play2-war-plugin][play2war], which supports Play 2.1 and is built from git master. For convenience, is hosted on the SQS Ivy repository included above.)

In `project/Build.scala`, add the following at the top of the file:

```scala
import com.blendlabsinc.sbtelasticbeanstalk.ElasticBeanstalk
import com.blendlabsinc.sbtelasticbeanstalk.ElasticBeanstalkKeys._
import com.github.play2war.plugin._
```

Add the following settings to your project:

```scala
val main = play.Project(appName, appVersion, appDependencies).settings(
  Play2WarKeys.servletVersion := "3.0",
  ebS3BucketName := "some-bucket-name",
  ebAppName := "some-app-name",
  ebEnvironmentName := "some-environment-name",
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


License
-------

This code is open source software licensed under the [Apache 2.0 License][apache2].

[apache2]: http://www.apache.org/licenses/LICENSE-2.0.html
[awseb]: http://aws.amazon.com/elasticbeanstalk/
[play2war]: https://github.com/dlecan/play2-war-plugin
