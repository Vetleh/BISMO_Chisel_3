package bismo

import chisel3._
import chisel3.util._
import chisel3.stage.ChiselStage
import sys.process._
import fpgatidbits.PlatformWrapper._
import fpgatidbits.TidbitsMakeUtils
import java.nio.file.Paths

// Main entry points into the different functionalities provided by this repo.
// There are four different entry points:
// - ChiselMain to generate Verilog
// - EmuLibMain to generate HW+SW cosimulations
// - DriverMain to generate register driver code for BISMO
// - CharacterizeMain to characterize FPGA resource usage of components

object Settings {
  type AccelInstFxn = PlatformWrapperParams => GenericAccelerator
  type AccelMap = Map[String, AccelInstFxn]

  val myInstParams = new BitSerialMatMulParams(
    dpaDimLHS = 8, dpaDimRHS = 8, dpaDimCommon = 256,
    lhsEntriesPerMem = 64 * 32 * 1024 / (8 * 256),
    rhsEntriesPerMem = 64 * 32 * 1024 / (8 * 256),
    mrp = PYNQZ1Params.toMemReqParams(),
    cmdQueueEntries = 256
  )
  val myInstFxn: AccelInstFxn = {
    (p: PlatformWrapperParams) => new BitSerialMatMulAccel(myInstParams, p)
  }

  def makeInstFxn(myP: BitSerialMatMulParams): AccelInstFxn = {
    return {(p: PlatformWrapperParams) => new BitSerialMatMulAccel(myP, p)}
  }

  def writeVerilogToFile(verilog: String, path: String) = {
      import java.io._
      val fname = path
      val f = new File(fname)
      if (!f.exists()) {
        f.getParentFile.mkdirs
        f.createNewFile()
      }
      val writer = new PrintWriter(f)
      writer.write(verilog)
      writer.close()

    }

  // accelerator emu settings
  var emuConfigParams =  new BitSerialMatMulParams(
    dpaDimLHS = 2, dpaDimRHS = 2, dpaDimCommon = 128, lhsEntriesPerMem = 128,
    rhsEntriesPerMem = 128, mrp = PYNQZ1Params.toMemReqParams()
  )

  // given accelerator or hw-sw-test name, return its hardware instantiator
  val emuP = TesterWrapperParams
  val emuMap: AccelMap = Map(
    // "main" is the emulator for the default target
    "main" -> {p => new BitSerialMatMulAccel(emuConfigParams, emuP)},
    // HW-SW cosimulation tests
    // for these tests (EmuTest*) the same name is assumed to be the cpp file
    // that defines the software part of the test under test/cosim
    "EmuTestExecStage" -> {p => new EmuTestExecStage(emuP)},
    "EmuTestFetchStage" -> {p => new EmuTestFetchStage(2, 2, emuP)},
    "EmuTestResultStage" -> {p => new EmuTestResultStage(2, emuP)}
  )
}

// call this object's main method to generate Chisel Verilog
object ChiselMain {
  def main(args: Array[String]): Unit = {
    val platformName: String = args(0)
    val targetDir: String = args(1)
    val dpaDimLHS: Int = args(2).toInt
    val dpaDimCommon: Int = args(3).toInt
    val dpaDimRHS: Int = args(4).toInt
    val accInst = Settings.makeInstFxn(
      new BitSerialMatMulParams(
        dpaDimLHS = dpaDimLHS, dpaDimRHS = dpaDimRHS, dpaDimCommon = dpaDimCommon,
        lhsEntriesPerMem = 64 * 32 * 1024 / (dpaDimLHS * dpaDimCommon),
        rhsEntriesPerMem = 64 * 32 * 1024 / (dpaDimRHS * dpaDimCommon),
        mrp = PYNQZ1Params.toMemReqParams()
      )
    )

    val platformInst = {f: (PlatformWrapperParams => GenericAccelerator) => new VerilatedTesterWrapper(f, targetDir)}
    // val accInst = {p: PlatformWrapperParams => new VaddStreamReactor(p,5) }

    val verilogString = (new chisel3.stage.ChiselStage).emitVerilog(platformInst(accInst))
    Settings.writeVerilogToFile(verilogString, targetDir + "/TesterWrapper.v")
    // val resRoot = Paths.get("src/main/resources").toAbsolutePath.toString
    // val resTestRoot = resRoot + "/TestVaddReactor"
    // fpgatidbits.TidbitsMakeUtils.fileCopy(resRoot + "/Makefile", targetDir)
    // fpgatidbits.TidbitsMakeUtils.fileCopy(resTestRoot + "/main.cpp", targetDir)
    // val platformInst = TidbitsMakeUtils.platformMap(platformName)

    // val chiselArgs = Array("v")
    // (new ChiselStage).emitVerilog(platformInst(accInst, "out_verilog/"), chiselArgs)
    // (new ChiselStage).execute(chiselArgs, firrtl.AnnotationSeq(() => Module(platformInst(accInst, ""))))
    // chiselMain(chiselArgs, () => Module(platformInst(accInst)))
  }
}


// call this object's main method to generate a C++ static library containing
// the cycle-accurate emulation model for the chosen accelerator. the interface
// of the model is compatible with  the fpgatidbits.PlatformWrapper hw/sw
// interface.
object EmuLibMain {
  def main(args: Array[String]): Unit = {
    val emuName: String = args(0)
    val emuDir: String = args(1)
    if (args.size > 2) {
        val dpaDimLHS: Int = args(2).toInt
        val dpaDimCommon: Int = args(3).toInt
        val dpaDimRHS: Int = args(4).toInt

        Settings.emuConfigParams = new BitSerialMatMulParams(
           dpaDimLHS = dpaDimLHS, dpaDimRHS = dpaDimRHS, dpaDimCommon = dpaDimCommon,
           lhsEntriesPerMem = 128, rhsEntriesPerMem = 128, mrp = PYNQZ1Params.toMemReqParams()
        )
    }
    val accInst: Settings.AccelInstFxn = Settings.emuMap(emuName)
    TidbitsMakeUtils.makeVerilator(accInst, emuDir)
  }
}
