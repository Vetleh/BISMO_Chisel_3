package bismo

import chisel3._
import chisel3.util._
import fpgatidbits.ocm._
import fpgatidbits.streams._
import fpgatidbits.PlatformWrapper._

// standalone, accelerator-wrapped version of ExecStage
// this provides the hardware side for EmuTestExecStage
class EmuTestExecStage(p: PlatformWrapperParams) extends GenericAccelerator(p) {
  val numMemPorts = 0
  // parameters for accelerator instance
  val myP = new ExecStageParams(
    dpaParams = new DotProductArrayParams(
      dpuParams = new DotProductUnitParams(
        pcParams = new PopCountUnitParams(numInputBits=64),
        accWidth = 32, maxShiftSteps = 16
      ), m = 2, n = 2
    ), lhsTileMem = 1024, rhsTileMem = 1024, tileMemAddrUnit = 1
  )

  class EmuTestExecStageIO extends GenericAcceleratorIF(numMemPorts, p) {
    // base control signals
    val start = Input(Bool())                   // hold high while running
    val done = Output(Bool())                   // high when done until start=0
    val cfg = new ExecStageCfgIO()
    val csr = Input(new ExecStageCtrlIO(myP))
    // write access to input matrix tile memory
    val tilemem_lhs_sel = Input(UInt(log2Up(myP.getM()).W))
    val tilemem_lhs_addr = Input(UInt(log2Up(myP.lhsTileMem).W))
    val tilemem_lhs_data = Input(UInt(myP.getK().W))
    val tilemem_lhs_write = Input(Bool())
    val tilemem_rhs_sel = Input(UInt(log2Up(myP.getN()).W))
    val tilemem_rhs_addr = Input(UInt(log2Up(myP.rhsTileMem).W))
    val tilemem_rhs_data = Input(UInt(myP.getK().W))
    val tilemem_rhs_write = Input(Bool())
    // access to result memory
    val resmem_addr_r = Input(UInt(log2Up(myP.getM()).W))
    val resmem_addr_c = Input(UInt(log2Up(myP.getN()).W))
    val resmem_addr_e = Input(UInt(log2Up(myP.resEntriesPerMem).W))
    val resmem_data = Output(UInt(myP.getResBitWidth().W))
  }

  val io = IO(new EmuTestExecStageIO()) 

  val rmm = Module(new ExecStage(myP)).io
  rmm.cfg <> io.cfg
  rmm.csr <> io.csr
  rmm.start := io.start
  io.done := rmm.done
  // the signature can be e.g. used for checking that the accelerator has the
  // correct version. here the signature is regenerated from the current date.
  io.signature := makeDefaultSignature()

  // tile memories
  val tilemem_lhs = VecInit.fill(myP.getM()) {
    Module(new PipelinedDualPortBRAM(
      addrBits = log2Up(myP.lhsTileMem), dataBits = myP.getK(),
      regIn = myP.bramInRegs, regOut = myP.bramOutRegs
    )).io
  }
  val tilemem_rhs = VecInit.fill(myP.getN()) {
    Module(new PipelinedDualPortBRAM(
      addrBits = log2Up(myP.rhsTileMem), dataBits = myP.getK(),
      regIn = myP.bramInRegs, regOut = myP.bramOutRegs
    )).io
  }
  // instantiate the result memory
  val resmem = VecInit.fill(myP.getM()) { VecInit.fill(myP.getN()) {
    Module(new PipelinedDualPortBRAM(
      addrBits = log2Up(myP.resEntriesPerMem), dataBits = myP.getResBitWidth(),
      regIn = 0, regOut = 0
    )).io
  }}
  // wire up direct access to tile mems
  // use port 0 for direct muxed access, port 1 for actually feeding the DPA
  for(i <- 0 until myP.getM()) {
    tilemem_lhs(i).ports(0).req.addr := io.tilemem_lhs_addr
    tilemem_lhs(i).ports(0).req.writeData := io.tilemem_lhs_data
    val myWriteEn = (io.tilemem_lhs_sel === i.U) & io.tilemem_lhs_write
    tilemem_lhs(i).ports(0).req.writeEn := myWriteEn

    tilemem_lhs(i).ports(1).req := rmm.tilemem.lhs_req(i)
    rmm.tilemem.lhs_rsp(i) := tilemem_lhs(i).ports(1).rsp
    //when(tilemem_lhs(i).ports(0).req.writeEn) { printf("BRAM %d write: addr %d data %x\n", UInt(i), tilemem_lhs(i).ports(0).req.addr, tilemem_lhs(i).ports(0).req.writeData) }
  }
  for(i <- 0 until myP.getN()) {
    tilemem_rhs(i).ports(0).req.addr := io.tilemem_rhs_addr
    tilemem_rhs(i).ports(0).req.writeData := io.tilemem_rhs_data
    val myWriteEn = (io.tilemem_rhs_sel === i.U) & io.tilemem_rhs_write
    tilemem_rhs(i).ports(0).req.writeEn := myWriteEn

    tilemem_rhs(i).ports(1).req := rmm.tilemem.rhs_req(i)
    rmm.tilemem.rhs_rsp(i) := tilemem_rhs(i).ports(1).rsp
  }
  // wire-up: result memory
  for{
    m <- 0 until myP.getM()
    n <- 0 until myP.getN()
  } {
    resmem(m)(n).ports(0).req := rmm.res.req(m)(n)
    resmem(m)(n).ports(1).req.addr := io.resmem_addr_e
    resmem(m)(n).ports(1).req.writeEn := false.B
    // resultStage.resmem_rsp(m)(n) := resmem(m)(n).ports(1).rsp
  }

  // register result reg selector inputs
  // resmem(io.resmem_addr_r)(io.resmem_addr_c).ports(1).req.addr :=
  io.resmem_data := resmem(io.resmem_addr_r)(io.resmem_addr_c).ports(1).rsp.readData

}