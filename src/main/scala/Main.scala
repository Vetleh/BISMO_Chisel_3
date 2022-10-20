package bismo

import chisel3._
import chisel3.util._
import chisel3.stage.ChiselStage
import sys.process._
import fpgatidbits.PlatformWrapper._
import fpgatidbits.TidbitsMakeUtils

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
    val platformInst = TidbitsMakeUtils.platformMap(platformName)

    val chiselArgs = Array("v", "--target-dir", targetDir)
    (new ChiselStage).emitVerilog(platformInst(accInst, ""), chiselArgs)
    // (new ChiselStage).execute(chiselArgs, firrtl.AnnotationSeq(() => Module(platformInst(accInst, ""))))
    // chiselMain(chiselArgs, () => Module(platformInst(accInst)))
  }
}
