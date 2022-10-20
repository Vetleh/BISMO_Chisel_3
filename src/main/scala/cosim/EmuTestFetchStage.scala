package bismo

import chisel3._
import chisel3.util._
import fpgatidbits.dma._
import fpgatidbits.ocm._
import fpgatidbits.streams._
import fpgatidbits.PlatformWrapper._

class EmuTestFetchStage(
  nLHS: Int, nRHS: Int, p: PlatformWrapperParams
) extends GenericAccelerator(p) {
  val numMemPorts = 1
  val myP = new FetchStageParams(
    numLHSMems = nLHS, numRHSMems = nRHS,
    numAddrBits = 10, mrp = PYNQZ1Params.toMemReqParams(), bramWrLat = 2
  )
  class EmuTestFetchStageIO extends GenericAcceleratorIF(numMemPorts, p) {
    // base control signals
    val start = Input(Bool())                   // hold high while running
    val done = Output(Bool())                   // high when done until start=0
    val perf = new FetchStagePerfIO(myP)
    val csr = Input(new FetchStageCtrlIO(myP))
    val bram_sel = Input(UInt(32.W))
    val bram_req = Input(new OCMRequest(myP.mrp.dataWidth, myP.numAddrBits))
    val bram_rsp = Output(UInt(myP.mrp.dataWidth.W))
  }

  val io = IO(new EmuTestFetchStageIO())
  io.signature := makeDefaultSignature()

  val brams = VecInit.fill(myP.numNodes) {
    Module(new PipelinedDualPortBRAM(
      addrBits = myP.numAddrBits, dataBits = myP.mrp.dataWidth,
      regIn = 1, regOut = 1
    )).io
  }

  val fetch = Module(new FetchStage(myP)).io
  fetch.start := io.start
  io.done := fetch.done
  fetch.csr <> io.csr
  fetch.perf <> io.perf
  for(i <- 0 until myP.numLHSMems) {
    brams(i).ports(0).req <> io.bram_req
    brams(i).ports(0).req.writeEn := (io.bram_sel === i.U) & io.bram_req.writeEn
    brams(i).ports(1).req <> fetch.bram.lhs_req(i)
  }
  for(i <- 0 until myP.numRHSMems) {
    val o = i + myP.numLHSMems
    brams(o).ports(0).req <> io.bram_req
    brams(o).ports(0).req.writeEn := (io.bram_sel === o.U) & io.bram_req.writeEn
    brams(o).ports(1).req <> fetch.bram.rhs_req(i)
  }
  io.bram_rsp := brams(io.bram_sel).ports(0).rsp.readData

  fetch.dram.rd_req <> io.memPort(0).memRdReq
  io.memPort(0).memRdRsp <> fetch.dram.rd_rsp

  // write ports are unused -- plug them to prevent Vivado synth errors
  for(i <- 0 until numMemPorts) {
    plugMemWritePort(i)
  }
}