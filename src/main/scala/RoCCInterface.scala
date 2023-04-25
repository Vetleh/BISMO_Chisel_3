package bismo
import chisel3._
import chisel3.util._
import fpgatidbits.PlatformWrapper._
import freechips.rocketchip.tile.HasCoreParameters
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.tile._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket.{TLBConfig, HellaCacheReq}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.system.DefaultConfig
import freechips.rocketchip.amba.axi4._

class BISMOAccel(opcodes: OpcodeSet, val n: Int = 50)(implicit p: Parameters)
    extends LazyRoCC(opcodes) {
  override lazy val module = new BISMOAccelImp(this)
  val l2mem = (0 until 1).map { x =>
    val y = LazyModule(new L2MemHelper())
    tlNode := y.masterNode
    y
  }
}

class BISMOAccelImp(outer: BISMOAccel)(implicit p: Parameters)
    extends LazyRoCCModuleImp(outer)
    with HasCoreParameters {
  val regfile = Reg(Vec(outer.n, UInt(xLen.W)))
  val busy = RegInit(VecInit(Seq.fill(outer.n) { false.B }))
  val cmd = Queue(io.cmd)

  val funct = cmd.bits.inst.funct
  val addr = cmd.bits.rs2(log2Up(outer.n) - 1, 0)
  val doWrite = funct === 0.U
  val doRead = funct === 1.U
  val doLoad = funct === 2.U
  val memRespTag = io.mem.resp.bits.tag(log2Up(outer.n) - 1, 0)

  // datapath
  val wdata = cmd.bits.rs1

  // Return 0 if nothing read related happens
  io.resp.bits.data := 0.U

  // Write and read data to the register
  when(cmd.fire && doWrite) {
    regfile(addr) := wdata
  }.elsewhen(cmd.fire && doRead) {
    io.resp.bits.data := regfile(addr)
  }

  val doResp = cmd.bits.inst.xd
  val stallReg = busy(addr)
  val stallLoad = doLoad && !io.mem.req.ready
  // val stallLoad = doLoad && !io.mem.req.ready
  val stallResp = doResp && !io.resp.ready

  // command resolved if no stalls AND not issuing a load that will need a request
  cmd.ready := !stallReg && !stallLoad && !stallResp

  // PROC RESPONSE INTERFACE
  // valid response if valid command, need a response, and no stalls
  io.resp.valid := cmd.valid && doResp && !stallReg && !stallLoad

  // Must respond with the appropriate tag or undefined behavior
  io.resp.bits.rd := cmd.bits.inst.rd

  // Semantics is to always send out prior accumulator register value
  // io.resp.bits.data := outputVal

  // Be busy when have pending memory requests or committed possibility of pending requests
  io.busy := cmd.valid || busy.reduce(_ || _)

  // Set this true to trigger an interrupt on the processor (please refer to supervisor documentation)
  io.interrupt := false.B

  // TODO hook all the registers up to bismo
  val bismoInitParams = new BitSerialMatMulParams(
    dpaDimLHS = 2,
    dpaDimRHS = 2,
    dpaDimCommon = 128,
    lhsEntriesPerMem = 128,
    rhsEntriesPerMem = 128,
    mrp = PYNQZ1Params.toMemReqParams()
  )

  // Instansiate BISMO
  val emuP = TesterWrapperParams
  val bismo = Module(new BitSerialMatMulAccel(bismoInitParams, emuP))
  val impl = bismo.io
  // wire up

  // Reset for accelerator
  bismo.reset.asBool
  when(regfile(0) === 1.U){
    bismo.reset := true.B
  }.otherwise {
    bismo.reset := false.B
  }

  // Signature of the accelerator
  impl.signature := regfile(1)

  // Stage enables
  impl.fetch_enable := regfile(2)
  impl.exec_enable := regfile(3)
  impl.result_enable := regfile(4)

  // Opcounts
  regfile(5) := impl.fetch_op_count
  regfile(6) := impl.exec_op_count
  regfile(7) := impl.result_op_count

  // Hardware info
  regfile(8) := impl.hw.readChanWidth
  regfile(9) := impl.hw.writeChanWidth
  regfile(10) := impl.hw.dpaDimLHS
  regfile(11) := impl.hw.dpaDimRHS
  regfile(12) := impl.hw.dpaDimCommon
  regfile(13) := impl.hw.lhsEntriesPerMem
  regfile(14) := impl.hw.rhsEntriesPerMem
  regfile(15) := impl.hw.accWidth
  regfile(16) := impl.hw.maxShiftSteps
  regfile(17) := impl.hw.cmdQueueEntries

  // CC
  regfile(18) := impl.perf.cc
  impl.perf.cc_enable := regfile(19)

  // perf fetch
  regfile(20) := impl.perf.prf_fetch.count
  impl.perf.prf_fetch.sel := regfile(21)

  // perf exec
  regfile(22) := impl.perf.prf_exec.count
  impl.perf.prf_exec.sel := regfile(23)

  // perf result
  regfile(24) := impl.perf.prf_res.count
  impl.perf.prf_res.sel := regfile(25)
  
  regfile(26) := impl.ins.ready 
  impl.ins.valid := regfile(27)
  impl.ins.bits.dram_base_lhs := regfile(28)
  impl.ins.bits.dram_base_rhs := regfile(29)
  impl.ins.bits.dram_block_offset_bytes := regfile(30)
  impl.ins.bits.dram_block_size_bytes := regfile(31)
  impl.ins.bits.dram_block_count := regfile(32)
  impl.ins.bits.tiles_per_row := regfile(33)

  impl.ins.bits.numTiles := regfile(34)
  impl.ins.bits.shiftAmount := regfile(35)
  impl.ins.bits.negate := regfile(36)

  impl.ins.bits.dram_base := regfile(37)
  impl.ins.bits.dram_skip := regfile(38)
  impl.ins.bits.wait_complete_bytes := regfile(39)

  impl.ins.bits.lhs_l0_per_l1 := regfile(40)
  impl.ins.bits.rhs_l0_per_l1 := regfile(41)
  impl.ins.bits.lhs_l1_per_l2 := regfile(42)
  impl.ins.bits.rhs_l1_per_l2 := regfile(43)
  impl.ins.bits.lhs_l2_per_matrix := regfile(44)
  impl.ins.bits.rhs_l2_per_matrix := regfile(45)
  impl.ins.bits.z_l2_per_matrix := regfile(46)
  impl.ins.bits.lhs_bytes_per_l2 := regfile(47)
  impl.ins.bits.rhs_bytes_per_l2 := regfile(48)
  impl.ins.bits.nrows_a := regfile(49)
  impl.ins.bits.dpa_z_bytes := regfile(50)
  

  val axi = outer.l2mem(0).module.io.axi
  // Memory requests
  // Read address axi
  impl.memPort(0).memRdReq.ready := axi.ar.ready
  axi.ar.valid := impl.memPort(0).memRdReq.valid
  axi.ar.bits.addr := impl.memPort(0).memRdReq.bits.addr
  axi.ar.bits.id := impl.memPort(0).memRdReq.bits.channelID
  // Burst is not supported so only 1 read at a time
  // AXI specifies amount of bursts as (len + 1)
  axi.ar.bits.len := 0.U
  axi.ar.bits.size := log2Ceil(bismoInitParams.mrp.dataWidth / 8).U

  // Read response axi
  axi.r.ready := impl.memPort(0).memRdRsp.ready
  impl.memPort(0).memRdRsp.valid := axi.r.valid
  impl.memPort(0).memRdRsp.bits.readData := axi.r.bits.data
  impl.memPort(0).memRdRsp.bits.isLast := axi.r.bits.last
  impl.memPort(0).memRdRsp.bits.channelID := axi.r.bits.id
  impl.memPort(0).memRdRsp.bits.isWrite := false.B
  impl.memPort(0).memRdRsp.bits.metaData := 0.U

  // MemWrReq
  impl.memPort(0).memWrReq.ready := axi.aw.ready
  axi.aw.valid := impl.memPort(0).memWrReq.valid
  axi.aw.bits.addr := impl.memPort(0).memWrReq.bits.addr
  axi.aw.bits.id := impl.memPort(0).memWrReq.bits.channelID
  // Burst is not supported so only 1 read at a time
  // AXI specifies amount of bursts as (len + 1)
  axi.aw.bits.len := 0.U
  axi.aw.bits.size := log2Ceil(bismoInitParams.mrp.dataWidth / 8).U

  // MemWrDat
  impl.memPort(0).memWrDat.ready := axi.w.ready
  axi.w.valid := impl.memPort(0).memWrDat.valid
  axi.w.bits.data := impl.memPort(0).memWrDat.bits
  axi.w.bits.strb := (Math.pow(2,(bismoInitParams.mrp.dataWidth / 8)).toInt - 1).U

  // MemWrRsp
  axi.b.ready := impl.memPort(0).memWrRsp.ready
  impl.memPort(0).memWrRsp.valid := axi.b.valid
  impl.memPort(0).memWrRsp.bits.isWrite := true.B
  impl.memPort(0).memWrRsp.bits.isLast := false.B
  impl.memPort(0).memWrRsp.bits.channelID := axi.b.bits.id
  impl.memPort(0).memWrRsp.bits.metaData := 0.U
  impl.memPort(0).memWrRsp.bits.readData := axi.b.bits.resp

  // MEMORY REQUEST INTERFACE
  // io.mem.req.valid := cmd.valid && doLoad && !stallReg && !stallResp
  // io.mem.req.bits.addr := addend
  // io.mem.req.bits.tag := addr
  // io.mem.req.bits.cmd := M_XRD // perform a load (M_XWR for stores)
  // io.mem.req.bits.size := log2Ceil(8).U
  // io.mem.req.bits.signed := false.B
  // io.mem.req.bits.data := 0.U // we're not performing any stores...
  // io.mem.req.bits.phys := false.B
  // io.mem.req.bits.dprv := cmd.bits.status.dprv
  // io.mem.req.bits.dv := cmd.bits.status.dv
}

class WithBISMOAccel
    extends Config((site, here, up) => { case BuildRoCC =>
      up(BuildRoCC) ++ Seq((p: Parameters) => {
        val bismo = LazyModule.apply(new BISMOAccel(OpcodeSet.custom0)(p))
        bismo
      })
    })