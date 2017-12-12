import sbt._
import Keys._
import sbt.Keys.publishTo
import sbt.Keys.version

lazy val sbtXjc = (project in file("."))
  .settings(
    name := "sbt-xjc",
    licenses += ("BSD 3-Clause", url(
      "http://opensource.org/licenses/BSD-3-Clause")),
    organization := "org.scala-sbt.plugins",
    version := "0.9-CWC",
    sbtPlugin := true,
    publishMavenStyle := false,

    //
    // Publishing settings
    //

    credentials in ThisBuild += Credentials(Path.userHome / ".sbt" / ".credentials"),

    publishTo in ThisBuild <<= version { (v: String) =>
      val nexus = "https://nexus.cwconsult.dk/content/repositories/"
      def repo(s: String): Resolver =
        Resolver.url(s, url(nexus + s))(
          Resolver.ivyStylePatterns)
      if (v.trim.endsWith("SNAPSHOT"))
        Some(repo("ivysnapshots"))
      else
        Some(repo("ivyreleases"))
    }

  )
  .settings(
    crossSbtVersions := Seq("0.13.16", "1.0.2"),
    sbtVersion in Global := "0.13.16",
    scalaCompilerBridgeSource := {
      val sv = appConfiguration.value.provider.id.version
      ("org.scala-sbt" % "compiler-interface" % sv % "component").sources
    }
  )
