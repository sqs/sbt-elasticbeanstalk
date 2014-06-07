resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("play" % "sbt-plugin" % "2.1-RC1")

resolvers += "Play2war plugins release" at "http://repository-play-war.forge.cloudbees.com/release/"

addSbtPlugin("com.github.play2war" % "play2-war-plugin" % "0.9-RC1")

addSbtPlugin("com.joescii" % "sbt-elasticbeanstalk-plugin" % "0.0.7-SNAPSHOT")
