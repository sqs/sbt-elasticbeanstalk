resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("play" % "sbt-plugin" % "2.1-RC1")

resolvers += Resolver.url("SQS Ivy", url("http://sqs.github.com/repo"))(Resolver.ivyStylePatterns)

// resolvers += Resolver.file("Local Ivy", file(Path.userHome + "/.ivy2/local"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.github.play2war" % "play2-war-plugin" % "0.9a-SNAPSHOT")

addSbtPlugin("com.blendlabsinc" % "sbt-elasticbeanstalk-plugin" % "0.0.4-SNAPSHOT")
