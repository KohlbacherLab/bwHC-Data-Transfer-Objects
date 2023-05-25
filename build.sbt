name := "bwhc-data-transfer-objects"
ThisBuild / organization := "de.bwhc"
ThisBuild / scalaVersion := "2.13.8"
ThisBuild / version      := "1.1-SNAPSHOT"

//-----------------------------------------------------------------------------
// PROJECTS
//-----------------------------------------------------------------------------

lazy val global = project
  .in(file("."))
  .settings(
    settings,
    publish / skip := true
  )
  .aggregate(
    dtos,
    generators
  )

lazy val dtos = project
  .settings(
    name := "mtb-dto-dtos",
    settings,
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-json" % "2.8.1"
    )
  )


lazy val generators = project
  .settings(
    name := "mtb-dto-generators",
    settings,
    libraryDependencies ++= Seq(
      "de.ekut.tbi" %% "generators" % "0.1-SNAPSHOT"
    )
  )
  .dependsOn(
    dtos
  )

//-----------------------------------------------------------------------------
// SETTINGS
//-----------------------------------------------------------------------------

lazy val settings = commonSettings

lazy val compilerOptions = Seq(
  "-encoding", "utf8",
  "-unchecked",
  "-feature",
  "-language:postfixOps",
  "-Xfatal-warnings",
  "-deprecation",
)

lazy val commonSettings = Seq(
  scalacOptions ++= compilerOptions,
  resolvers ++= Seq("Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository") ++
    Resolver.sonatypeOssRepos("releases") ++
    Resolver.sonatypeOssRepos("snapshots")
)

