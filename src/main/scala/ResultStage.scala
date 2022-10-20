package bismo

import chisel3._
import chisel3.util._
import fpgatidbits.dma._
import fpgatidbits.ocm._
import fpgatidbits.streams._
import fpgatidbits.PlatformWrapper._

// ResultStage is one of the three components of the BISMO pipeline, which
// contains infrastructure to concurrently write result data to DRAM from DPA
// accumulators while the other stages are doing their thing.
// The ResultStage is responsible for writing one or more rows of accumulators
// into DRAM. Internally, once a row is finished, the write addr
// will jump to the next address determined by the runtime matrix size.

class ResultStageParams(
    val accWidth: Int, // accumulator width in bits
    // DPA dimensions
    val dpa_rhs: Int,
    val dpa_lhs: Int,
    val mrp: MemReqParams, // memory request params for platform
    // read latency for result memory
    val resMemReadLatency: Int,
    // whether to transpose accumulator order while writing
    val transpose: Boolean = true,
    // number of entries in the result mem
    val resEntriesPerMem: Int = 2
) {
  def headersAsList(): List[String] = {
    return List("DRAM_wr", "dpa_rhs", "dpa_lhs", "accWidth")
  }

  def contentAsList(): List[String] = {
    return List(mrp.dataWidth, dpa_rhs, dpa_lhs, accWidth).map(_.toString)
  }

  // total width of all accumulator registers
  def getTotalAccBits(): Int = {
    return accWidth * dpa_rhs * dpa_lhs
  }

  // number of DPA rows
  def getNumRows(): Int = {
    // TODO respect transposition here
    assert(transpose == true)
    return dpa_rhs
  }

  // one row worth of acc. register bits
  def getRowAccBits(): Int = {
    return accWidth * dpa_lhs
  }

  // sanity check parameters
  // channel width must divide row acc bits
  assert(getRowAccBits() % mrp.dataWidth == 0)
  assert(transpose == true)
  // TODO add wait states for resmem read latency to handle this
  assert(resMemReadLatency == 0)
}

class ResultStageCtrlIO(myP: ResultStageParams) extends Bundle {
  // DRAM controls
  val dram_base = UInt(64.W)
  val dram_skip = UInt(64.W)
  // wait for completion of all writes (no new DRAM wr generated)
  val waitComplete = Bool()
  val waitCompleteBytes = UInt(32.W)
  // result memory to read from
  val resmem_addr = UInt(log2Up(myP.resEntriesPerMem).W)
}

class ResultStageDRAMIO(myP: ResultStageParams) extends Bundle {
  // DRAM write channel
  val wr_req = Decoupled(new GenericMemoryRequest(myP.mrp))
  val wr_dat = Decoupled(UInt(myP.mrp.dataWidth.W))
  val wr_rsp = Flipped(Decoupled(new GenericMemoryResponse(myP.mrp)))

}

class ResultStage(val myP: ResultStageParams) extends Module {
  val io = IO(new Bundle {
    // base control signals
    val start = Input(Bool()) // hold high while running
    val done = Output(Bool()) // high when done until start=0
    val csr = Input(new ResultStageCtrlIO(myP))
    val dram = new ResultStageDRAMIO(myP)
    // interface towards result memory
    val resmem_req = Vec(myP.dpa_lhs, Vec(myP.dpa_rhs, Output(new OCMRequest(myP.accWidth, log2Up(myP.resEntriesPerMem)))))
    val resmem_rsp = Vec(myP.dpa_lhs, Vec(myP.dpa_rhs, Input(new OCMResponse(myP.accWidth))))
  })
  // TODO add burst support, single beat for now
  val bytesPerBeat: Int = myP.mrp.dataWidth / 8

  // instantiate downsizer
  val ds = Module(
    new StreamResizer(
      myP.getTotalAccBits(),
      myP.mrp.dataWidth
    )
  ).io
  // instantiate request generator
  val rg = Module(
    new BlockStridedRqGen(
      mrp = myP.mrp,
      writeEn = true
    )
  ).io

  // wire up resmem_req
  for {
    i <- 0 until myP.dpa_lhs
    j <- 0 until myP.dpa_rhs
  } {
    io.resmem_req(i)(j).addr := io.csr.resmem_addr
    io.resmem_req(i)(j).writeEn := false.B
  }

  // wire up downsizer
  val accseq = for {
    j <- 0 until myP.getNumRows()
    i <- 0 until myP.dpa_lhs
  } yield io.resmem_rsp(i)(j).readData
  val allacc = Cat(accseq.reverse)
  ds.in.TDATA := allacc
  // downsizer input valid controlled by FSM
  ds.in.TVALID := false.B
  FPGAQueue(Decoupled(ds.out), 256) <> io.dram.wr_dat

  // wire up request generator
  rg.in.valid := false.B
  rg.in.bits.base := io.csr.dram_base
  rg.in.bits.block_step := io.csr.dram_skip
  rg.in.bits.block_count := myP.getNumRows().U
  // TODO fix if we introduce bursts here
  rg.block_intra_step := bytesPerBeat.U
  rg.block_intra_count := (myP.getRowAccBits() / (8 * bytesPerBeat)).U

  rg.out <> io.dram.wr_req

  // completion detection logic
  val regCompletedWrBytes = RegInit(0.U(32.W))
  io.dram.wr_rsp.ready := true.B
  when(io.dram.wr_rsp.valid) {
    regCompletedWrBytes := regCompletedWrBytes + bytesPerBeat.U
  }
  val allComplete = (regCompletedWrBytes === io.csr.waitCompleteBytes)

  // FSM logic for control
  val sIdle :: sWaitDS :: sWaitRG :: sWaitComplete :: sFinished :: Nil =
    Enum(5)
  val regState = RegInit(sIdle)

  io.done := false.B

  switch(regState) {
    is(sIdle) {
      when(io.start) {
        when(io.csr.waitComplete) {
          regState := sWaitComplete
        }.otherwise {
          ds.in.TVALID := true.B
          rg.in.valid := true.B
          when(ds.in.TREADY & !rg.in.ready) {
            regState := sWaitRG
          }.elsewhen(!ds.in.TREADY & rg.in.ready) {
            regState := sWaitDS
          }.elsewhen(ds.in.TREADY & rg.in.ready) {
            regState := sFinished
          }
        }
      }
    }

    is(sWaitDS) {
      // downsizer is busy but request generator is done
      ds.in.TVALID := true.B
      when(ds.in.TREADY) { regState := sFinished }
    }

    is(sWaitRG) {
      // downsizer is done but request generator busy
      rg.in.valid := true.B
      when(rg.in.ready) { regState := sFinished }
    }

    is(sWaitComplete) {
      when(allComplete) { regState := sFinished }
    }

    is(sFinished) {
      io.done := true.B
      when(!io.start) { regState := sIdle }
    }
  }
  // debug:
  // uncomment to print issued write requests and data in emulation
  // PrintableBundleStreamMonitor(io.dram.wr_req, Bool(true), "wr_req", true)
  /*when(io.dram.wr_dat.fire()) {
    printf("Write data: %x\n", io.dram.wr_dat.bits)
  }*/
}
