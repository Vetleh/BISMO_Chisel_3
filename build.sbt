// ThisBuild / scalaVersion := "2.12.12"
// ThisBuild / version := "0.1.0"
// ThisBuild / organization := "com.github.vetleh"
// name := "bismo"

val chiselVersion = "3.5.1"

// lazy val root = (project in file("."))
//   .settings(
//     name := "bismo",
//     libraryDependencies ++= Seq(
//       "edu.berkeley.cs" %% "chisel3" % chiselVersion,
//       "edu.berkeley.cs" %% "chiseltest" % "0.5.1" % "test",
//       "edu.berkeley.cs" %% "chisel-iotesters" % "2.5.4"
//     ),
//     scalacOptions ++= Seq(
//       "-language:reflectiveCalls",
//       "-deprecation",
//       "-feature",
//       "-Xcheckinit",
//       "-P:chiselplugin:genBundleElements",
//       "-language:postfixOps"
//     ),
//     addCompilerPlugin(
//       "edu.berkeley.cs" % "chisel3-plugin" % chiselVersion cross CrossVersion.full
//     )
//   )

name := "bismo"

version := "1.1.0"

scalaVersion := "2.12.10"

libraryDependencies ++= Seq(
      "edu.berkeley.cs" %% "chisel3" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % "0.5.1" % "test",
      "edu.berkeley.cs" %% "chisel-iotesters" % "2.5.4"
    )

// add fpga-tidbits as unmanaged source dependency, pulled as git submodule
Compile / unmanagedSourceDirectories += baseDirectory.value / "fpga-tidbits" / "src" / "main" / "scala"
// fpga-tidbits stores compile scripts, drivers etc. in the resource dir
Compile / unmanagedResourceDirectories += baseDirectory.value / "fpga-tidbits" / "src" / "main" / "resources"
