package bismo

import chisel3._
import chisel3.util._

import fpgatidbits.dma._
import fpgatidbits.ocm._
import fpgatidbits.streams._
import fpgatidbits.PlatformWrapper._

// ExecStage is one of thre three components of the BISMO pipeline, which
// contains infrastructure to concurrently fetch data from DRAM into BRAMs while
// the other stages are doing their thing.

class FetchStageParams(
    val numLHSMems: Int, // number of LHS memories
    val numRHSMems: Int, // number of RHS memories
    val numAddrBits: Int, // number of bits for address inside target memory
    val mrp: MemReqParams, // memory request params for platform
    val bramWrLat: Int = 1 // number of cycles until data written to BRAM
) {
  def headersAsList(): List[String] = {
    return List("nodes", "rd_width")
  }

  def contentAsList(): List[String] = {
    return List(numNodes, mrp.dataWidth).map(_.toString)
  }

  // total number of nodes (LHS or RHS mems) targeted by the FetchStage
  val numNodes = numLHSMems + numRHSMems

  // number of ID bits to identify a node
  def getIDBits(): Int = {
    return log2Up(getNumNodes())
  }

  // total number of bits for identifying packet target
  def getTotalRouteBits(): Int = {
    return getIDBits() + numAddrBits
  }

  // number of nodes
  def getNumNodes(): Int = {
    return numNodes
  }

  // number of max cycles between when data is emitted from the DMA
  // engine (StreamReader) until it is written into its target BRAM
  def getDMAtoBRAMLatency(): Int = {
    // 1 cycle per hop in the interconnect
    val max_interconnect_cycles = getNumNodes()
    val routegen_cycles = 1 // due to queuing between routegen and interconnect
    return routegen_cycles + max_interconnect_cycles + bramWrLat
  }
}

// data fetched from DRAM is combined with destination memory information from
// the address generator and made into a FetchStagePacket to be transported, one
// hop at a time, through the network into its target memory. the network uses
// the id field of the packet to determine if the target node has been reached.
class FetchStagePacket(myP: FetchStageParams) extends PrintableBundle {
  val data = Bits(myP.mrp.dataWidth.W)
  val id = UInt(myP.getIDBits().W)
  val addr = UInt(myP.numAddrBits.W)

  val printfStr = "(id = %d, addr = %d, data = %x)\n"
  val printfElems = { () => Seq(id, addr, data) }
}

class FetchInterconnect(val myP: FetchStageParams) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new FetchStagePacket(myP)))
    val node_out = Output(
      Vec(myP.getNumNodes(), new OCMRequest(myP.mrp.dataWidth, myP.numAddrBits))
    )
  })
  // the interconnect delivers data directly to BRAMs, which are deterministic
  // and do not cause any backpressure. therefore, the interconnect can always
  // deliver packets.
  io.in.ready := true.B
  // shift register stages along which we will carry packets
  // the valid reg chain must be initialized to all zeroes to avoid garbage writes

  // val regNodeValid = VecInit.fill(myP.getNumNodes()) { RegInit(false.B) }

  val regNodeValid = RegInit(VecInit(Seq.fill(myP.getNumNodes())(false.B)))

  // the packet data reg does not have init to save LUTs
  // TODO Had to change this to reginit, is there an fix that still saves LUTs?
  val regNodePacket = RegInit(VecInit(Seq.fill(myP.getNumNodes())(io.in.bits)))

  for (i <- 0 until myP.getNumNodes()) {
    if (i == 0) {
      regNodeValid(0) := io.in.valid
      regNodePacket(0) := io.in.bits
    } else {
      regNodeValid(i) := regNodeValid(i - 1)
      regNodePacket(i) := regNodePacket(i - 1)
    }
    // addr and data are propagated to all nodes without dest. checking
    io.node_out(i).addr := regNodePacket(i).addr
    io.node_out(i).writeData := regNodePacket(i).data
    // the correct destination is the only node that gets its write enable
    io.node_out(i).writeEn := ((regNodePacket(i).id === i.U) && regNodeValid(i))
  }
}

// fetch stage IO: performance counters
class FetchStagePerfIO(myP: FetchStageParams) extends Bundle {
  // clock cycles from start to done
  val cycles = Output(UInt(32.W))

}

// fetch stage IO: controls to BRAM and DRAM
class FetchStageCtrlIO(myP: FetchStageParams) extends PrintableBundle {
  // DRAM fetch config
  // base address for all fetch groups
  val dram_base = UInt(64.W)
  // size of each block (contiguous read) from DRAM
  val dram_block_size_bytes = UInt(32.W)
  // offset (in bytes) to start of next block in DRAM
  val dram_block_offset_bytes = UInt(32.W)
  // number of blocks to fetch for each group
  val dram_block_count = UInt(32.W)

  // router config
  // tiles per row (number of writes before going to next BRAM)
  val tiles_per_row = UInt(16.W)
  // base BRAM address to start from for writes
  val bram_addr_base = UInt(myP.numAddrBits.W)
  // ID of BRAM to start from
  val bram_id_start = UInt(myP.getIDBits().W)
  // ID range of BRAM to end at. start+range will be included.
  val bram_id_range = UInt(myP.getIDBits().W)

  val printfStr =
    "(dram (base = %x, bsize = %d, boffs = %d, bcnt = %d), bramstart = %d, bramrange = %d, tiles = %d)\n"
  val printfElems = { () =>
    Seq(
      dram_base,
      dram_block_size_bytes,
      dram_block_offset_bytes,
      dram_block_count,
      bram_id_start,
      bram_id_range,
      tiles_per_row
    )
  }
}

// fetch stage IO: BRAM writes
class FetchStageBRAMIO(myP: FetchStageParams) extends Bundle {
  val lhs_req = Vec(myP.numLHSMems, Output(new OCMRequest(myP.mrp.dataWidth, myP.numAddrBits)))
  val rhs_req = Vec(myP.numRHSMems, Output(new OCMRequest(myP.mrp.dataWidth, myP.numAddrBits))) 
    

}

// fetch stage IO: DRAM reads
class FetchStageDRAMIO(myP: FetchStageParams) extends Bundle {
  val rd_req = Decoupled(new GenericMemoryRequest(myP.mrp))
  val rd_rsp = Flipped(Decoupled(new GenericMemoryResponse(myP.mrp)))

}

class FetchRouteGen(myP: FetchStageParams) extends Module {
  val io = IO(new Bundle {
    // controls =============================================
    // prepare for new sequence (re-initialize internal state)
    val init_new = Input(Bool())
    // tiles per row (number of writes before going to next BRAM)
    val tiles_per_row = Input(UInt(16.W))
    // base BRAM address to start from for writes
    val bram_addr_base = Input(UInt(myP.numAddrBits.W))
    // ID of BRAM to start from
    val bram_id_start = Input(UInt(myP.getIDBits().W))
    // ID range of BRAM to end at. start+range will be included
    val bram_id_range = Input(UInt(myP.getIDBits().W))
    // stream I/O ==========================================
    val in = Flipped(Decoupled(Bits(myP.mrp.dataWidth.W)))
    val out = Decoupled(new FetchStagePacket(myP))
  })
  // registers for values controlling sequence generation
  val regTilesPerRow = RegInit(0.U(16.W))
  val regTilesPerRowMinusOne = RegInit(0.U(16.W))
  val regBRAMStart = RegInit(0.U(myP.getIDBits().W))
  val regBRAMRange = RegInit(0.U(myP.getIDBits().W))
  val regAddrBase = RegInit(0.U(myP.numAddrBits.W))
  // internal state registers for sequence generation
  val regAddr = RegInit(0.U(myP.numAddrBits.W))
  val regBRAMTarget = RegInit(0.U(myP.getIDBits().W))

  // wire up I/O streams
  io.out.valid := io.in.valid
  io.in.ready := io.out.ready
  io.out.bits.data := io.in.bits
  io.out.bits.id := regBRAMStart + regBRAMTarget
  io.out.bits.addr := regAddrBase + regAddr

  when(io.init_new) {
    // get new values for I/O signals
    regTilesPerRow := io.tiles_per_row
    regTilesPerRowMinusOne := io.tiles_per_row - 1.U
    regBRAMStart := io.bram_id_start
    regBRAMRange := io.bram_id_range
    regAddrBase := io.bram_addr_base
    regAddr := 0.U
    regBRAMTarget := 0.U
  }

  when(io.in.valid & io.out.ready) {
    // update route generation state
    val addr_is_at_end = (regAddr === regTilesPerRowMinusOne)
    val bram_is_at_end = (regBRAMTarget === regBRAMRange)
    val bram_incr = Mux(bram_is_at_end, 0.U, regBRAMTarget + 1.U)
    regAddr := Mux(addr_is_at_end, 0.U, regAddr + 1.U)
    regBRAMTarget := Mux(addr_is_at_end, bram_incr, regBRAMTarget)
    regAddrBase := regAddrBase + Mux(
      bram_is_at_end & addr_is_at_end,
      regTilesPerRow,
      0.U
    )
  }
}

class FetchStage(val myP: FetchStageParams) extends Module {
  val io = IO(new Bundle {
    // base control signals
    val start = Input(Bool()) // hold high while running
    val done = Output(Bool()) // high when done until start=0
    val csr = Input(new FetchStageCtrlIO(myP))
    val perf = new FetchStagePerfIO(myP)
    val bram = new FetchStageBRAMIO(myP)
    val dram = new FetchStageDRAMIO(myP)
  })

  /*when(io.start) {
    printf("Fetch stage runcfg: " + io.csr.printfStr, io.csr.printfElems():_*)
  }*/
  // instantiate FetchGroup components
  val reader = Module(new BlockStridedRqGen(myP.mrp, false, 0)).io
  val routegen = Module(new FetchRouteGen(myP)).io
  val conn = Module(new FetchInterconnect(myP)).io

  // wire up the BlockStridedRqGen
  val startPulseRising = io.start & RegNext(!io.start)
  reader.in.valid := startPulseRising
  // outer loop
  reader.in.bits.base := RegNext(io.csr.dram_base)
  // bytes to jump between each block
  reader.in.bits.block_step := RegNext(io.csr.dram_block_offset_bytes)
  // number of blocks
  reader.in.bits.block_count := RegNext(io.csr.dram_block_count)
  // inner loop
  // bytes for beat for each block: currently 1-beat bursts, can try 8
  val bytesPerBeat = myP.mrp.dataWidth / 8
  Predef.assert(isPow2(bytesPerBeat))
  val bytesToBeatsRightShift = log2Up(bytesPerBeat)
  reader.block_intra_step := bytesPerBeat.U
  // #beats for each block
  reader.block_intra_count := RegNext(
    io.csr.dram_block_size_bytes >> bytesToBeatsRightShift
  )

  // supply read requests to DRAM from BlockStridedRqGen
  reader.out <> io.dram.rd_req
  // filter read responses from DRAM and push into route generator
  ReadRespFilter(io.dram.rd_rsp) <> routegen.in

  // wire up routing info generation
  // use rising edge detect on start to set init_new high for 1 cycle
  routegen.init_new := io.start & !RegNext(io.start, false.B)
  routegen.tiles_per_row := io.csr.tiles_per_row
  routegen.bram_addr_base := io.csr.bram_addr_base
  routegen.bram_id_start := io.csr.bram_id_start
  routegen.bram_id_range := io.csr.bram_id_range
  FPGAQueue(routegen.out, 2) <> conn.in
  // assign IDs to LHS and RHS memories for interconnect
  // 0...numLHSMems-1 are the LHS IDs
  // numLHSMems..numLHSMems+numRHSMems-1 are the RHS IDs
  for (i <- 0 until myP.numLHSMems) {
    val lhs_mem_ind = i
    val node_ind = i
    io.bram.lhs_req(lhs_mem_ind) := conn.node_out(node_ind)
    println(s"LHS $lhs_mem_ind assigned to node# $node_ind")
  }

  for (i <- 0 until myP.numRHSMems) {
    val rhs_mem_ind = i
    val node_ind = myP.numLHSMems + i
    io.bram.rhs_req(rhs_mem_ind) := conn.node_out(node_ind)
    println(s"RHS $rhs_mem_ind assigned to node# $node_ind")
  }

  // count responses to determine done
  val regBlockBytesReceived = RegInit(0.U(32.W))
  val regBlocksReceived = RegInit(0.U(32.W))
  val regBlockBytesAlmostFinished =
    RegNext(io.csr.dram_block_size_bytes - bytesPerBeat.U)
  when(io.start) {
    when(routegen.in.valid & routegen.in.ready) {
      // count responses
      when(regBlockBytesReceived === regBlockBytesAlmostFinished) {
        regBlockBytesReceived := 0.U
        regBlocksReceived := regBlocksReceived + 1.U
      }.otherwise {
        regBlockBytesReceived := regBlockBytesReceived + bytesPerBeat.U
      }
    }
  }
  val dmaDone = io.start & (regBlocksReceived === io.csr.dram_block_count)
  // reset byte counter when just completed
  val startPulseFalling = !io.start & RegNext(io.start)
  when(startPulseFalling) {
    regBlockBytesReceived := 0.U
    regBlocksReceived := 0.U
  }

  // signal done when BRAM writes are complete
  io.done := ShiftRegister(dmaDone, myP.getDMAtoBRAMLatency())

  // performance counters
  // clock cycles from start to done
  val regCycles = Reg(UInt(32.W))
  when(!io.start) { regCycles := 0.U }
    .elsewhen(io.start & !io.done) { regCycles := regCycles + 1.U }
  io.perf.cycles := regCycles
}
