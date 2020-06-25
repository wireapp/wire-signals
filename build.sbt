name := "wire-signals"
organization in ThisBuild := "com.wire"

version := "0.1"

scalaVersion in ThisBuild := "2.11.12"

resolvers += Resolver.sonatypeRepo("releases")
resolvers += Resolver.mavenLocal

libraryDependencies ++= Seq(
  "org.threeten"                  %  "threetenbp"            % "1.3.+"            % Provided,
  "org.scala-lang"                %  "scala-reflect"          % (scalaVersion in ThisBuild).value % Provided,
  "org.scala-lang"                %  "scala-compiler"        % (scalaVersion in ThisBuild).value % Provided,

  //Test dependencies
  "org.scalatest"                 %% "scalatest"             % "3.0.7"            % Test,
  "org.scalamock"                 %% "scalamock"             % "4.2.0"            % Test,
  "junit"                         %  "junit"                 % "4.8.2"            % Test
)

scalacOptions ++= Seq("-deprecation", "-feature")

homepage := Some(url("https://github.com/wireapp/wire-signals"))
licenses := Seq("GPL 3.0" -> url("https://www.gnu.org/licenses/gpl-3.0.en.html"))
publishMavenStyle := true
publishArtifact in Test := false
pomIncludeRepository := { _ => false }
publishTo in ThisBuild := {
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

publishMavenStyle := true

publishConfiguration := publishConfiguration.value.withOverwrite(true)
publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true)
publishM2Configuration := publishM2Configuration.value.withOverwrite(true)
