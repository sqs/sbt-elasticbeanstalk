addSbtPlugin("play" % "sbt-plugin" % "2.1-RC1")

resolvers += Resolver.file("Local Ivy", file(Path.userHome + "/.ivy2/local"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.blendlabsinc" % "sbt-elasticbeanstalk-plugin" % "0.0.1-SNAPSHOT")
