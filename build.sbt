name := "wire-signals"

version := "0.1"

scalaVersion := "2.12.8"

resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies ++= Seq(
  "org.threeten"                  %  "threetenbp"            % "1.3.+"            % Provided,
  "org.scala-lang"                %  "scala-reflect"         % (scalaVersion in ThisBuild).value % Provided,
  "org.scala-lang"                %  "scala-compiler"        % (scalaVersion in ThisBuild).value % Provided,
  
  //Test dependencies
  "org.scalatest"                 %% "scalatest"             % "3.0.5"            % Test,
  "org.scalamock"                 %% "scalamock"             % "4.1.0"            % Test,
  "junit"                         %  "junit"                 % "4.8.2"            % Test
)

scalacOptions ++= Seq("-deprecation", "-feature")