addSbtPlugin("play" % "sbt-plugin" % "2.1-RC1")

addSbtPlugin("com.github.play2war" % "play2-war-plugin" % "0.9-SNAPSHOT")

resolvers += Resolver.file("Local Ivy", file(Path.userHome + "/.ivy2/local"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.blendlabsinc" % "sbt-elasticbeanstalk-plugin" % "0.0.1-SNAPSHOT")
