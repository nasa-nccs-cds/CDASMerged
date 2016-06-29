import sbt._

object Version {
  val logback   = "1.1.2"
  val mockito   = "1.10.19"
  val scala     = "2.11.7"
  val scalaTest = "2.2.4"
  val slf4j     = "1.7.6"
}

object Library {
  val logbackClassic = "ch.qos.logback"    %  "logback-classic" % Version.logback
  val mockitoAll     = "org.mockito"       %  "mockito-all"     % Version.mockito
  val scalaTest      = "org.scalatest"     %% "scalatest"       % Version.scalaTest
  val slf4jApi       = "org.slf4j"         %  "slf4j-api"       % Version.slf4j
  val scalaxml       = "org.scala-lang.modules" %% "scala-xml"  % "1.0.3"
  val scalaparser    = "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.3"
  val cds2           = "nasa.nccs"         %% "cdas2"       % "1.2-SNAPSHOT"
  val scalactic      = "org.scalactic" %% "scalactic" % "2.2.6"
  val scalatest      = "org.scalatest" %% "scalatest" % "2.2.6" % "test"
}

object Dependencies {
  import Library._

  val scala = Seq( logbackClassic, slf4jApi, scalaxml, scalaparser, scalactic, scalatest )

  val CDS2 = Seq( cds2 )
}
