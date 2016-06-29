lazy val cdServices = project
lazy val cdWps = project
lazy val cdShell = project

lazy val commonSettings = Seq(
  organization := "com.example",
  version := "0.1.0",
  scalaVersion := "2.11.7"
)

lazy val cdServices = (project in file("cdServices")).
  settings(commonSettings: _*).
  settings(
    // other settings
  )

lazy val cdWps = (project in file("cdWps")).
  settings(commonSettings: _*).
  settings(
    // other settings
  )

lazy val cdShell = (project in file("cdShell")).
  settings(commonSettings: _*).
  settings(
    // other settings
  )

lazy val root = (project in file(".")).aggregate( cdServices, cdWps, cdShell )