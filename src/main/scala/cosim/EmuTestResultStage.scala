package bismo

import chisel3._
import chisel3.util._
import fpgatidbits.dma._
import fpgatidbits.ocm._
import fpgatidbits.streams._
import fpgatidbits.PlatformWrapper._

class EmuTestResultStage(
  accArrayDim: Int, p: PlatformWrapperParams
) extends GenericAccelerator(p) {
  val numMemPorts = 1
  // parameters for accelerator instance
  val myP = new ResultStageParams(
    accWidth = 32, resMemReadLatency = 0,
    dpa_rhs = accArrayDim, dpa_lhs = accArrayDim, mrp = PYNQZ1Params.toMemReqParams()
  )

  class EmuTestResultStageIO() extends GenericAcceleratorIF(numMemPorts, p) {
      // base control signals
      val start = Input(Bool())                   // hold high while running
      val done = Output(Bool())                   // high when done until start=0
      val csr = Input(new ResultStageCtrlIO(myP))
      val accwr_en = Input(Bool())
      val accwr_lhs = Input(UInt(log2Up(myP.dpa_lhs).W))
      val accwr_rhs = Input(UInt(log2Up(myP.dpa_rhs).W))
      val accwr_data = Input(UInt(myP.accWidth.W))
    }

  val io = IO(new EmuTestResultStageIO())
  
  val resmem = VecInit.fill(myP.dpa_lhs) { VecInit.fill(myP.dpa_rhs) {
    Module(new PipelinedDualPortBRAM(
      addrBits = 1, dataBits = myP.accWidth, regIn = 0, regOut = 0
    )).io
  }}
  val res = Module(new ResultStage(myP)).io
  res.start := io.start
  io.done := res.done
  res.csr <> io.csr
  for(lhs <- 0 until myP.dpa_lhs) {
    for(rhs <- 0 until myP.dpa_rhs) {
      // drive defaults on resmem req port 0
      val is_my_lhs = (lhs.U === io.accwr_lhs)
      val is_my_rhs = (rhs.U === io.accwr_rhs)
      val is_my_write = is_my_lhs & is_my_rhs
      // write enable selected resmem entry
      resmem(lhs)(rhs).ports(0).req.writeEn := is_my_write & io.accwr_en
      resmem(lhs)(rhs).ports(0).req.addr := 0.U
      resmem(lhs)(rhs).ports(0).req.writeData := io.accwr_data
      // connect resmem port 1 directly to ResultStage interface
      res.resmem_req(lhs)(rhs) <> resmem(lhs)(rhs).ports(1).req
      resmem(lhs)(rhs).ports(1).rsp <> res.resmem_rsp(lhs)(rhs)
    }
  }

  // connect DRAM interface for ResultStage
  res.dram.wr_req <> io.memPort(0).memWrReq
  res.dram.wr_dat <> io.memPort(0).memWrDat
  io.memPort(0).memWrRsp <> res.dram.wr_rsp
  // plug unused read port
  plugMemReadPort(0)
  // the signature can be e.g. used for checking that the accelerator has the
  // correct version. here the signature is regenerated from the current date.
  io.signature := makeDefaultSignature()
}