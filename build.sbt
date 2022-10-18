// See README.md for license details.

ThisBuild / scalaVersion := "2.13.8"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "com.github.vetleh"

val chiselVersion = "3.5.3"

lazy val root = (project in file("."))
  .settings(
    name := "BISMO_Chisel_3",
    libraryDependencies ++= Seq(
      "edu.berkeley.cs" %% "chisel3" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % "0.5.1" % "test",
      "edu.berkeley.cs" %% "chisel-iotesters" % "2.5.4"
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-P:chiselplugin:genBundleElements",
      "-language:postfixOps"
    ),
    addCompilerPlugin(
      "edu.berkeley.cs" % "chisel3-plugin" % chiselVersion cross CrossVersion.full
    )
  )
// add fpga-tidbits as unmanaged source dependency, pulled as git submodule
unmanagedSourceDirectories in Compile += baseDirectory.value / "fpga-tidbits" / "src" / "main" / "scala"
// fpga-tidbits stores compile scripts, drivers etc. in the resource dir
unmanagedResourceDirectories in Compile += baseDirectory.value / "fpga-tidbits" / "src" / "main" / "resources"
