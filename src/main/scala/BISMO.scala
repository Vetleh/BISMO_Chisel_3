package bismo

import chisel3._
import chisel3.util._
import fpgatidbits.PlatformWrapper._
import fpgatidbits.dma._
import fpgatidbits.ocm._
import fpgatidbits.streams._

// This is the top-level source file that cobbles together the stages,
// controllers and token queues into a BISMO instance.
// The key Module here is BitSerialMatMulAccel, whose configuration is
// specified by the BitSerialMatMulParams.

// make the instantiated config options available to softare at runtime
class BitSerialMatMulHWCfg(bitsPerField: Int) extends Bundle {
  val readChanWidth = UInt(bitsPerField.W)
  val writeChanWidth = UInt(bitsPerField.W)
  val dpaDimLHS = UInt(bitsPerField.W)
  val dpaDimRHS = UInt(bitsPerField.W)
  val dpaDimCommon = UInt(bitsPerField.W)
  val lhsEntriesPerMem = UInt(bitsPerField.W)
  val rhsEntriesPerMem = UInt(bitsPerField.W)
  val accWidth = UInt(bitsPerField.W)
  val maxShiftSteps = UInt(bitsPerField.W)
  val cmdQueueEntries = UInt(bitsPerField.W)

}

// parameters that control the accelerator instantiation
class BitSerialMatMulParams(
    val dpaDimLHS: Int,
    val dpaDimRHS: Int,
    val dpaDimCommon: Int,
    val lhsEntriesPerMem: Int,
    val rhsEntriesPerMem: Int,
    val mrp: MemReqParams,
    val resEntriesPerMem: Int = 2,
    val bramPipelineBefore: Int = 1,
    val bramPipelineAfter: Int = 1,
    val extraRegs_DPA: Int = 0,
    val extraRegs_DPU: Int = 0,
    val extraRegs_PC: Int = 0,
    val accWidth: Int = 32,
    val maxShiftSteps: Int = 16,
    val cmdQueueEntries: Int = 16,
    // do not instantiate the shift stage
    val noShifter: Boolean = false,
    // do not instantiate the negate stage
    val noNegate: Boolean = false
) {
  def headersAsList(): List[String] = {
    return List(
      "dpaLHS",
      "dpaRHS",
      "dpaCommon",
      "lhsMem",
      "rhsMem",
      "DRAM_rd",
      "DRAM_wr",
      "noShifter",
      "noNegate",
      "extraRegDPA",
      "extraRegDPU",
      "extraRegPC"
    )
  }

  def contentAsList(): List[String] = {
    return List(
      dpaDimLHS,
      dpaDimRHS,
      dpaDimCommon,
      lhsEntriesPerMem,
      rhsEntriesPerMem,
      mrp.dataWidth,
      mrp.dataWidth,
      noShifter,
      noNegate,
      extraRegs_DPA,
      extraRegs_DPU,
      extraRegs_PC
    ).map(_.toString)
  }

  def asHWCfgBundle(bitsPerField: Int): BitSerialMatMulHWCfg = {
    val ret = new BitSerialMatMulHWCfg(bitsPerField)
    ret.readChanWidth := mrp.dataWidth.U
    ret.writeChanWidth := mrp.dataWidth.U
    ret.dpaDimLHS := dpaDimLHS.U
    ret.dpaDimRHS := dpaDimRHS.U
    ret.dpaDimCommon := dpaDimCommon.U
    ret.lhsEntriesPerMem := lhsEntriesPerMem.U
    ret.rhsEntriesPerMem := rhsEntriesPerMem.U
    ret.accWidth := accWidth.U
    ret.maxShiftSteps := maxShiftSteps.U
    ret.cmdQueueEntries := cmdQueueEntries.U
    return ret
  }

  val fetchStageParams = new FetchStageParams(
    numLHSMems = dpaDimLHS,
    numRHSMems = dpaDimRHS,
    numAddrBits = log2Up(
      math
        .max(lhsEntriesPerMem, rhsEntriesPerMem) * dpaDimCommon / mrp.dataWidth
    ),
    mrp = mrp,
    bramWrLat = bramPipelineBefore
  )
  val pcParams = new PopCountUnitParams(
    numInputBits = dpaDimCommon,
    extraPipelineRegs = extraRegs_PC
  )
  val dpuParams = new DotProductUnitParams(
    pcParams = pcParams,
    accWidth = accWidth,
    maxShiftSteps = maxShiftSteps,
    noShifter = noShifter,
    noNegate = noNegate,
    extraPipelineRegs = extraRegs_DPU
  )
  val dpaParams = new DotProductArrayParams(
    dpuParams = dpuParams,
    m = dpaDimLHS,
    n = dpaDimRHS,
    extraPipelineRegs = extraRegs_DPA
  )
  Predef.assert(dpaDimCommon >= mrp.dataWidth)
  val execStageParams = new ExecStageParams(
    dpaParams = dpaParams,
    lhsTileMem = lhsEntriesPerMem,
    rhsTileMem = rhsEntriesPerMem,
    bramInRegs = bramPipelineBefore,
    bramOutRegs = bramPipelineAfter,
    resEntriesPerMem = resEntriesPerMem,
    tileMemAddrUnit = dpaDimCommon / mrp.dataWidth
  )
  val resultStageParams = new ResultStageParams(
    accWidth = accWidth,
    dpa_lhs = dpaDimLHS,
    dpa_rhs = dpaDimRHS,
    mrp = mrp,
    resEntriesPerMem = resEntriesPerMem,
    resMemReadLatency = 0
  )
}

// Bundle to expose performance counter data to the CPU
class BitSerialMatMulPerf(myP: BitSerialMatMulParams) extends Bundle {
  val cc = Output(UInt(32.W))
  val cc_enable = Input(Bool())
  val prf_fetch = new Bundle {
    val count = Output(UInt(32.W))
    val sel = Input(UInt(log2Up(4).W))
  }
  val prf_exec = new Bundle {
    val count = Output(UInt(32.W))
    val sel = Input(UInt(log2Up(4).W))
  }
  val prf_res = new Bundle {
    val count = Output(UInt(32.W))
    val sel = Input(UInt(log2Up(4).W))
  }

}

class BitSerialMatMulAccel(
    val myP: BitSerialMatMulParams,
    p: PlatformWrapperParams
) extends GenericAccelerator(p) {

  val numMemPorts = 1

  class BitSerialMatMulAccelIO() extends GenericAcceleratorIF(numMemPorts, p) {
    // enable/disable execution for each stage
    val fetch_enable = Input(Bool())
    val exec_enable = Input(Bool())
    val result_enable = Input(Bool())
    // op queues
    val fetch_op = Flipped(Decoupled(new ControllerCmd(1, 1)))
    val exec_op = Flipped(Decoupled(new ControllerCmd(2, 2)))
    val result_op = Flipped(Decoupled(new ControllerCmd(1, 1)))
    // config for run ops
    val fetch_runcfg = Flipped(
      Decoupled(
        new FetchStageCtrlIO(myP.fetchStageParams)
      )
    )
    val exec_runcfg = Flipped(
      Decoupled(new ExecStageCtrlIO(myP.execStageParams))
    )
    val result_runcfg = Flipped(
      Decoupled(
        new ResultStageCtrlIO(myP.resultStageParams)
      )
    )
    // command counts in each queue
    val fetch_op_count = Output(UInt(32.W))
    val exec_op_count = Output(UInt(32.W))
    val result_op_count = Output(UInt(32.W))
    // instantiated hardware config
    val hw = Output(new BitSerialMatMulHWCfg(32))
    // performance counter I/O
    val perf = new BitSerialMatMulPerf(myP)
  }

  val io = IO(new BitSerialMatMulAccelIO)

  io.hw := myP.asHWCfgBundle(32)
  // instantiate accelerator stages
  val fetchStage = Module(new FetchStage(myP.fetchStageParams)).io
  val execStage = Module(new ExecStage(myP.execStageParams)).io
  val resultStage = Module(new ResultStage(myP.resultStageParams)).io
  // instantiate the controllers for each stage
  val fetchCtrl = Module(new FetchController(myP.fetchStageParams)).io
  val execCtrl = Module(new ExecController(myP.execStageParams)).io
  val resultCtrl = Module(new ResultController(myP.resultStageParams)).io
  // instantiate op and runcfg queues
  val fetchOpQ = Module(new FPGAQueue(io.fetch_op.bits, myP.cmdQueueEntries)).io
  val execOpQ = Module(new FPGAQueue(io.exec_op.bits, myP.cmdQueueEntries)).io
  val resultOpQ = Module(
    new FPGAQueue(io.result_op.bits, myP.cmdQueueEntries)
  ).io
  val fetchRunCfgQ = Module(
    new FPGAQueue(io.fetch_runcfg.bits, myP.cmdQueueEntries)
  ).io
  val execRunCfgQ = Module(
    new FPGAQueue(io.exec_runcfg.bits, myP.cmdQueueEntries)
  ).io
  val resultRunCfgQ = Module(
    new FPGAQueue(io.result_runcfg.bits, myP.cmdQueueEntries)
  ).io
  // instantiate tile memories
  val tilemem_lhs = VecInit.fill(myP.dpaDimLHS) {
    Module(
      new AsymPipelinedDualPortBRAM(
        p = new OCMParameters(
          b = myP.lhsEntriesPerMem * myP.dpaDimCommon,
          rWidth = myP.dpaDimCommon,
          wWidth = myP.mrp.dataWidth,
          pts = 2,
          lat = 0
        ),
        regIn = myP.bramPipelineBefore,
        regOut = myP.bramPipelineAfter
      )
    ).io
  }
  val tilemem_rhs = VecInit.fill(myP.dpaDimRHS) {
    Module(
      new AsymPipelinedDualPortBRAM(
        p = new OCMParameters(
          b = myP.rhsEntriesPerMem * myP.dpaDimCommon,
          rWidth = myP.dpaDimCommon,
          wWidth = myP.mrp.dataWidth,
          pts = 2,
          lat = 0
        ),
        regIn = myP.bramPipelineBefore,
        regOut = myP.bramPipelineAfter
      )
    ).io
  }
  // instantiate the result memory
  // TODO ResultStage actually assumes this memory can be read with zero
  // latency but current impl has latency of 1. this will cause bugs if reading
  // two different addresses in consecutive cycles.
  val resmem = VecInit.fill(myP.dpaDimLHS, myP.dpaDimRHS) {
    Module(
      new PipelinedDualPortBRAM(
        addrBits = log2Up(myP.resEntriesPerMem),
        dataBits = myP.accWidth,
        regIn = 0,
        regOut = 0
      )
    ).io
  }

  // instantiate synchronization token FIFOs
  val syncFetchExec_free = Module(new FPGAQueue(Bool(), 8)).io
  val syncFetchExec_filled = Module(new FPGAQueue(Bool(), 8)).io
  val syncExecResult_free = Module(new FPGAQueue(Bool(), 8)).io
  val syncExecResult_filled = Module(new FPGAQueue(Bool(), 8)).io

  // helper function to wire-up DecoupledIO to DecoupledIO with pulse generator
  def enqPulseGenFromValid[T <: Data](
      enq: DecoupledIO[T],
      vld: DecoupledIO[T]
  ) = {
    enq.valid := vld.valid & !RegNext(vld.valid)
    enq.bits := vld.bits
    vld.ready := enq.ready
  }

  // wire-up: command queues and pulse generators for fetch stage
  fetchCtrl.enable := io.fetch_enable
  io.fetch_op_count := fetchOpQ.count
  fetchOpQ.deq <> fetchCtrl.op
  fetchRunCfgQ.deq <> fetchCtrl.runcfg
  enqPulseGenFromValid(fetchOpQ.enq, io.fetch_op)
  enqPulseGenFromValid(fetchRunCfgQ.enq, io.fetch_runcfg)

  // wire-up: command queues and pulse generators for exec stage
  execCtrl.enable := io.exec_enable
  io.exec_op_count := execOpQ.count
  execOpQ.deq <> execCtrl.op
  execRunCfgQ.deq <> execCtrl.runcfg
  enqPulseGenFromValid(execOpQ.enq, io.exec_op)
  enqPulseGenFromValid(execRunCfgQ.enq, io.exec_runcfg)

  // wire-up: command queues and pulse generators for result stage
  resultCtrl.enable := io.result_enable
  io.result_op_count := resultOpQ.count
  resultOpQ.deq <> resultCtrl.op
  resultRunCfgQ.deq <> resultCtrl.runcfg
  enqPulseGenFromValid(resultOpQ.enq, io.result_op)
  enqPulseGenFromValid(resultRunCfgQ.enq, io.result_runcfg)

  // wire-up: fetch controller and stage
  fetchStage.start := fetchCtrl.start
  fetchCtrl.done := fetchStage.done
  fetchStage.csr := fetchCtrl.stageO
  // wire-up: exec controller and stage
  execStage.start := execCtrl.start
  execCtrl.done := execStage.done
  execStage.csr := execCtrl.stageO
  // wire-up: result controller and stage
  resultStage.start := resultCtrl.start
  resultCtrl.done := resultStage.done
  resultStage.csr := resultCtrl.stageO

  // wire-up: read channels to fetch stage
  fetchStage.dram.rd_req <> io.memPort(0).memRdReq
  io.memPort(0).memRdRsp <> fetchStage.dram.rd_rsp
  // wire-up: BRAM ports (fetch and exec stages)
  // port 0 used by fetch stage for writes
  // port 1 used by execute stage for reads
  for (m <- 0 until myP.dpaDimLHS) {
    tilemem_lhs(m).ports(0).req := fetchStage.bram.lhs_req(m)
    tilemem_lhs(m).ports(1).req := execStage.tilemem.lhs_req(m)
    execStage.tilemem.lhs_rsp(m) := tilemem_lhs(m).ports(1).rsp
    // when(tilemem_lhs(m).ports(0).req.writeEn) { printf("LHS BRAM %d write: addr %d data %x\n", UInt(m), tilemem_lhs(m).ports(0).req.addr, tilemem_lhs(m).ports(0).req.writeData) }
  }
  for (m <- 0 until myP.dpaDimRHS) {
    tilemem_rhs(m).ports(0).req := fetchStage.bram.rhs_req(m)
    tilemem_rhs(m).ports(1).req := execStage.tilemem.rhs_req(m)
    execStage.tilemem.rhs_rsp(m) := tilemem_rhs(m).ports(1).rsp
    // when(tilemem_rhs(m).ports(0).req.writeEn) { printf("RHS BRAM %d write: addr %d data %x\n", UInt(m), tilemem_rhs(m).ports(0).req.addr, tilemem_rhs(m).ports(0).req.writeData) }
  }
  // wire-up: shared resource management (fetch and exec controllers)
  execCtrl.sync_out(0) <> syncFetchExec_free.enq
  syncFetchExec_free.deq <> fetchCtrl.sync_in(0)
  fetchCtrl.sync_out(0) <> syncFetchExec_filled.enq
  syncFetchExec_filled.deq <> execCtrl.sync_in(0)

  // wire-up: shared resource management (exec and result stages)
  resultCtrl.sync_out(0) <> syncExecResult_free.enq
  syncExecResult_free.deq <> execCtrl.sync_in(1)
  execCtrl.sync_out(1) <> syncExecResult_filled.enq
  syncExecResult_filled.deq <> resultCtrl.sync_in(0)

  // wire-up: result memory (exec and result stages)
  for {
    m <- 0 until myP.dpaDimLHS
    n <- 0 until myP.dpaDimRHS
  } {
    resmem(m)(n).ports(0).req := execStage.res.req(m)(n)
    resmem(m)(n).ports(1).req := resultStage.resmem_req(m)(n)
    resultStage.resmem_rsp(m)(n) := resmem(m)(n).ports(1).rsp
  }
  // wire-up: write channels from result stage
  resultStage.dram.wr_req <> io.memPort(0).memWrReq
  resultStage.dram.wr_dat <> io.memPort(0).memWrDat
  io.memPort(0).memWrRsp <> resultStage.dram.wr_rsp

  // set default signature
  io.signature := makeDefaultSignature()

  // performance counters
  val regCCEnablePrev = RegNext(io.perf.cc_enable)
  val regCC = RegInit(0.U(32.W))
  io.perf.cc := regCC
  // reset cycle counter on rising edge of cc_enable
  when(io.perf.cc_enable & !regCCEnablePrev) { regCC := 0.U }
    // increment cycle counter while cc_enable is high
    .elsewhen(io.perf.cc_enable & regCCEnablePrev) { regCC := regCC + 1.U }

  fetchCtrl.perf.start := io.perf.cc_enable
  execCtrl.perf.start := io.perf.cc_enable
  resultCtrl.perf.start := io.perf.cc_enable
  io.perf.prf_fetch <> fetchCtrl.perf
  io.perf.prf_exec <> execCtrl.perf
  io.perf.prf_res <> resultCtrl.perf

  /* TODO expose the useful ports from the monitors below:
  StreamMonitor(syncFetchExec_free.enq, io.perf.cc_enable)
  StreamMonitor(syncFetchExec_free.deq, io.perf.cc_enable)
  StreamMonitor(syncFetchExec_filled.enq, io.perf.cc_enable)
  StreamMonitor(syncFetchExec_filled.deq, io.perf.cc_enable)
  StreamMonitor(syncExecResult_free.enq, io.perf.cc_enable)
  StreamMonitor(syncExecResult_free.deq, io.perf.cc_enable)
  StreamMonitor(syncExecResult_filled.enq, io.perf.cc_enable)
  StreamMonitor(syncExecResult_filled.deq, io.perf.cc_enable)
   */
}

class ResultBufParams(
    val addrBits: Int,
    val dataBits: Int,
    val regIn: Int,
    val regOut: Int
) {
  def headersAsList(): List[String] = {
    return List(
      "addBits",
      "dataBits",
      "regIn",
      "regOut"
    )
  }

  def contentAsList(): List[String] = {
    return List(
      addrBits,
      dataBits,
      regIn,
      regOut
    ).map(_.toString)
  }
}

// wrapper around PipelinedDualPortBRAM, here for characterization purposes
class ResultBuf(val myP: ResultBufParams) extends Module {
  val io = IO(new DualPortBRAMIO(myP.addrBits, myP.dataBits))

  val mem = Module(
    new PipelinedDualPortBRAM(
      addrBits = myP.addrBits,
      dataBits = myP.dataBits,
      regIn = myP.regIn,
      regOut = myP.regOut
    )
  ).io
  io <> mem
}
