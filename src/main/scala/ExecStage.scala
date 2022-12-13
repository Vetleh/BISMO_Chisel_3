package bismo

import chisel3._
import chisel3.util._
import fpgatidbits.ocm._
import fpgatidbits.streams._
import fpgatidbits.PlatformWrapper._

// ExecStage is one of thre three components of the BISMO pipeline, which
// contains the actual DotProductArray, BRAMs for input matrix storage, and
// SequenceGenerators to pull data from BRAMs into the compute array

class ExecStageParams(
    // parameters for the DotProductArray
    val dpaParams: DotProductArrayParams,
    // number of L0 tiles that can be stored on-chip for LHS and RHS matrices
    val lhsTileMem: Int,
    val rhsTileMem: Int,
    // how much to increment the tile mem address to go to next tile (due to
    // asymmetric BRAM between fetch/execute)
    val tileMemAddrUnit: Int,
    // levels of registers before (on address input) and after (on data output)
    // of each tile memory BRAM
    val bramInRegs: Int = 1,
    val bramOutRegs: Int = 1,
    // number of entries in the result mem
    val resEntriesPerMem: Int = 2
) {
  def headersAsList(): List[String] = {
    return dpaParams.headersAsList() ++ List("lhsTileMem", "rhsTileMem")
  }

  def contentAsList(): List[String] = {
    return dpaParams.contentAsList() ++ List(lhsTileMem, rhsTileMem).map(
      _.toString
    )
  }

  // latency of instantiated DPA
  val dpaLatency: Int = dpaParams.getLatency()
  // contributed latency of DPA due to BRAM pipelining
  // addr/data pipeline regs plus 1 because BRAM is inherently sequential
  val myLatency_read: Int = bramInRegs + bramOutRegs + 1
  // write latency to result memory
  val myLatency_write: Int = 0
  def getLatency(): Int = {
    return myLatency_read + myLatency_write + dpaLatency
  }
  def getBRAMReadLatency(): Int = {
    return myLatency_read
  }
  def getBRAMWriteLatency(): Int = {
    return myLatency_write
  }

  // convenience functions to access child parameters
  // LHS rows
  def getM(): Int = {
    return dpaParams.m
  }
  // popcount width
  def getK(): Int = {
    return dpaParams.dpuParams.pcParams.numInputBits
  }
  // RHS rows
  def getN(): Int = {
    return dpaParams.n
  }
  // number of bits per result word
  def getResBitWidth(): Int = {
    return dpaParams.dpuParams.accWidth
  }
}

// interface to hardware config available to software
class ExecStageCfgIO() extends Bundle {

  val config_dpu_x = Output(UInt(32.W))
  val config_dpu_y = Output(UInt(32.W))
  val config_dpu_z = Output(UInt(32.W))
  val config_lhs_mem = Output(UInt(32.W))
  val config_rhs_mem = Output(UInt(32.W))

}

// interface towards controller for the execute stage
class ExecStageCtrlIO(myP: ExecStageParams) extends PrintableBundle {
  val lhsOffset = UInt(32.W) // start offset for LHS tiles
  val rhsOffset = UInt(32.W) // start offset for RHS tiles
  val numTiles = UInt(32.W) // num of L0 tiles to execute
  // how much left shift to use
  val shiftAmount =
    UInt(log2Up(myP.dpaParams.dpuParams.maxShiftSteps + 1).W)
  // negate during accumulation
  val negate = Bool()
  // clear accumulators prior to first accumulation
  val clear_before_first_accumulation = Bool()
  // write to result memory at the end of current execution
  val writeEn = Bool()
  // result memory address to use for writing
  val writeAddr = UInt(log2Up(myP.resEntriesPerMem).W)

  val printfStr = "(offs lhs/rhs = %d/%d, ntiles = %d, << %d, w? %d/%d)\n"
  val printfElems = { () =>
    Seq(
      lhsOffset,
      rhsOffset,
      numTiles,
      shiftAmount,
      writeEn,
      writeAddr
    )
  }
}

// interface towards tile memories (LHS/RHS BRAMs)
class ExecStageTileMemIO(myP: ExecStageParams) extends Bundle {
  val lhs_req = Vec(myP.getM(), Output(new OCMRequest(myP.getK(),log2Up(myP.lhsTileMem * myP.tileMemAddrUnit))))
  val lhs_rsp = Vec(myP.getM(), Input(new OCMResponse(myP.getK())))
  val rhs_req = Vec(myP.getN(), Output(new OCMRequest(myP.getK(),log2Up(myP.rhsTileMem * myP.tileMemAddrUnit))))
  val rhs_rsp = Vec(myP.getN(), Input(new OCMResponse(myP.getK())))

}

// interface towards result stage
class ExecStageResMemIO(myP: ExecStageParams) extends Bundle {
  val req = Vec(myP.getM(), Vec(myP.getN(), Output(new OCMRequest(myP.getResBitWidth(), log2Up(myP.resEntriesPerMem)))))
}

class ExecStage(val myP: ExecStageParams) extends Module {
  val io = IO(new Bundle {
    // base control signals
    val start = Input(Bool()) // hold high while running
    val done = Output(Bool()) // high when done until start=0
    val cfg = new ExecStageCfgIO()
    val csr = Input(new ExecStageCtrlIO(myP))
    val tilemem = new ExecStageTileMemIO(myP)
    val res = new ExecStageResMemIO(myP)
  })
  // expose generated hardware config to software
  io.cfg.config_dpu_x := myP.getM().U
  io.cfg.config_dpu_y := myP.getN().U
  io.cfg.config_dpu_z := myP.getK().U
  io.cfg.config_lhs_mem := myP.lhsTileMem.U
  io.cfg.config_rhs_mem := myP.rhsTileMem.U
  // the actual compute array
  val dpa = Module(new DotProductArray(myP.dpaParams)).io
  // instantiate sequence generator for BRAM addressing
  // the tile mem is addressed in terms of the narrowest access
  val tileAddrBits = log2Up(
    myP.tileMemAddrUnit * math.max(myP.lhsTileMem, myP.rhsTileMem)
  )
  val seqgen = Module(new SequenceGenerator(tileAddrBits)).io
  seqgen.init := 0.U
  seqgen.count := io.csr.numTiles
  seqgen.step := myP.tileMemAddrUnit.U
  seqgen.start := io.start
  // account for latency inside datapath to delay finished signal
  io.done := ShiftRegister(seqgen.finished, myP.getLatency())

  // wire up the generated sequence into the BRAM address ports, and returned
  // read data into the DPA inputs
  for (i <- 0 to myP.getM() - 1) {
    io.tilemem.lhs_req(i).addr := seqgen.seq.bits + (io.csr.lhsOffset)
    io.tilemem.lhs_req(i).writeEn := false.B
    // TODOv2 this line wasent needed in original
    io.tilemem.lhs_req(i).writeData := 0.U
    dpa.a(i) := io.tilemem.lhs_rsp(i).readData
    // printf("Read data from BRAM %d = %x\n", UInt(i), io.tilemem.lhs_rsp(i).readData)
    /*when(seqgen.seq.valid) {
      printf("LHS BRAM %d read addr %d\n", UInt(i), io.tilemem.lhs_req(i).addr)
      printf("Seqgen: %d offset: %d\n", seqgen.seq.bits, io.csr.lhsOffset)
    }*/
  }
  for (i <- 0 to myP.getN() - 1) {
    io.tilemem.rhs_req(i).addr := seqgen.seq.bits + (io.csr.rhsOffset)
    io.tilemem.rhs_req(i).writeEn := false.B
    // TODOv2 this line wasent needed in the original
    io.tilemem.rhs_req(i).writeData := 0.U
    dpa.b(i) := io.tilemem.rhs_rsp(i).readData
    /*when(seqgen.seq.valid) {
      printf("RHS BRAM %d read addr %d\n", UInt(i), io.tilemem.rhs_req(i).addr)
      printf("Seqgen: %d offset: %d\n", seqgen.seq.bits, io.csr.rhsOffset)
    }*/
  }
  // no backpressure in current design, so always pop
  seqgen.seq.ready := true.B
  // data to DPA is valid after read from BRAM is completed
  val read_complete = ShiftRegister(seqgen.seq.valid, myP.getBRAMReadLatency())
  dpa.valid := read_complete
  dpa.shiftAmount := io.csr.shiftAmount
  dpa.negate := io.csr.negate
  // FIXME: this is not a great way to implement the accumulator clearing. if
  // there is a cycle of no data from the BRAM (due to a SequenceGenerator bug
  // or some weird timing interaction) an erronous accumulator clear may be
  // generated here.
  when(io.csr.clear_before_first_accumulation) {
    // set clear_acc to 1 for the very first cycle
    dpa.clear_acc := read_complete & !RegNext(read_complete)
  }.otherwise {
    dpa.clear_acc := false.B
  }

  // generate result memory write signal
  val time_to_write = myP.dpaLatency + myP.myLatency_read
  val do_write =
    ShiftRegister(io.start & seqgen.finished & io.csr.writeEn, time_to_write)
  val do_write_pulse = do_write & !RegNext(do_write)
  // wire up DPA accumulators to resmem write ports
  for {
    i <- 0 until myP.getM()
    j <- 0 until myP.getN()
  } {
    io.res.req(i)(j).writeData := dpa.out(i)(j).asUInt
    io.res.req(i)(j).addr := io.csr.writeAddr
    io.res.req(i)(j).writeEn := do_write_pulse
  }
  /*
  when(io.start & !Reg(next=io.start)) {
    printf("Now executing:\n")
    printf(io.csr.printfStr, io.csr.printfElems():_*)
  }
   */
  /*
  when(do_write_pulse) {
    printf("Exec stage writing into resmem %d:\n", io.csr.writeAddr)
    for {
      i <- 0 until myP.getM()
      j <- 0 until myP.getN()
    } {
      printf("%d ", dpa.out(i)(j))
    }
    printf("\n")
  }
   */
}
