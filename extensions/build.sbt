// based on http://caryrobbins.com/dev/sbt-publishing/

lazy val scala213 = "2.13.4"
lazy val scala212 = "2.12.12"
lazy val scala211 = "2.11.12"
lazy val supportedScalaVersions = List(scala213, scala212, scala211)

ThisBuild / organization := "com.wire"
ThisBuild / scalaVersion := scala213

val standardOptions = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-encoding", "utf8"
)

val scala211Options = Seq(
  "-optimise"
)

val scala212Options = Seq(
  "-opt:unreachable-code",
  "-opt:simplify-jumps",
  "-opt:compact-locals",
  "-opt:copy-propagation",
  "-opt:redundant-casts",
  "-opt:box-unbox",
  "-opt:nullness-tracking",
  "-opt:closure-invocations"
)

val scala213Options = Seq(
  "-opt:unreachable-code",
  "-opt:simplify-jumps",
  "-opt:compact-locals",
  "-opt:copy-propagation",
  "-opt:redundant-casts",
  "-opt:box-unbox",
  "-opt:nullness-tracking",
  "-opt:closure-invocations",
  "-opt:allow-skip-core-module-init",
  "-opt:assume-modules-non-null",
  "-opt:allow-skip-class-loading",
  "-opt:inline"
)

homepage := Some(url("https://github.com/wireapp/wire-signals"))
licenses := Seq("GPL 3.0" -> url("https://www.gnu.org/licenses/gpl-3.0.en.html"))
publishMavenStyle := true
publishArtifact in Test := false
pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

scmInfo := Some(
  ScmInfo(
    url("https://github.com/wireapp/wire-signals"),
    "scm:git:git@github.com:wireapp/wire-signals.git"
  )
)

developers := List(
  Developer("makingthematrix", "Maciej Gorywoda", "maciej.gorywoda@wire.com", url("https://github.com/makingthematrix"))
)

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("public"),
  Resolver.mavenLocal
)

publishMavenStyle := true

publishConfiguration      := publishConfiguration.value.withOverwrite(true)
publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true)
publishM2Configuration    := publishM2Configuration.value.withOverwrite(true)

Test / scalaVersion := scala213

lazy val root = (project in file("."))
  .settings(
    name := "wire-signals-extensions",
    crossScalaVersions := supportedScalaVersions,
    libraryDependencies ++= Seq(
      "com.wire" %% "wire-signals" % "0.4.0" % Provided,
      "org.threeten" %  "threetenbp" % "1.4.4" % Provided,
      //Test dependencies
      "org.scalameta" %% "munit" % "0.7.21" % Test
    ),
    scalacOptions ++= standardOptions ++ {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 11)) => scala211Options
        case Some((2, 12)) => scala212Options
        case _             => scala213Options
      }
    }
  )

testFrameworks += new TestFramework("munit.Framework")

mimaPreviousArtifacts := Set("com.wire" %% "wire-signals-extensions" % "0.4.0")
