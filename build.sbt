// See README.md for license details.

ThisBuild / scalaVersion := "2.12.12"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "com.github.vetleh"

def scalacOptionsVersion(scalaVersion: String): Seq[String] = {
  Seq() ++ {
    // If we're building with Scala > 2.11, enable the compile option
    //  switch to support our anonymous Bundle definitions:
    //  https://github.com/scala/bug/issues/10047
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((2, scalaMajor: Long)) if scalaMajor < 12 => Seq()
      case _ => Seq("-Xsource:2.11")
    }
  }
}

val chiselVersion = "3.4.+"

lazy val root = (project in file("."))
  .settings(
    name := "BISMO_Chisel_3",
    libraryDependencies ++= Seq(
      "edu.berkeley.cs" %% "chisel3" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.0.5" % "test"
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit"
    )
  )

val defaultVersions = Seq(
  "chisel-iotesters" -> "1.5.+",
  "chiseltest" -> "0.2.1+"
)

libraryDependencies ++= defaultVersions.map { case (dep, ver) =>
  "edu.berkeley.cs" %% dep % sys.props.getOrElse(dep + "Version", ver)
}

scalacOptions ++= scalacOptionsVersion(scalaVersion.value)

// add fpga-tidbits as unmanaged source dependency, pulled as git submodule
unmanagedSourceDirectories in Compile += baseDirectory.value / "fpga-tidbits" / "src" / "main" / "scala"
// fpga-tidbits stores compile scripts, drivers etc. in the resource dir
unmanagedResourceDirectories in Compile += baseDirectory.value / "fpga-tidbits" / "src" / "main" / "resources"
