package bismo

import chisel3._
import chisel3.util._
import fpgatidbits.PlatformWrapper._
import chisel3.experimental.{IntParam, BaseModule}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.config.{Parameters, Field, Config}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper.{HasRegMap, RegField}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.UIntIsOneOf
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.subsystem._
import fpgatidbits.dma.GenericMemoryMasterPort
import fpgatidbits.ocm.OCMRequest

import testchipip.TLHelper
// import fpgatidbits.dma.MemReqParamsTileLink

case object BISMOFrontBusExtraBuffers extends Field[Int](0)

case class BISMOTileLinkParams(
    address: BigInt = 0x90020000L,
    raddress: BigInt = 0x10040000L,
    width: Int = 32
)

case object BISMOKey extends Field[Option[BISMOTileLinkParams]](None)

trait BISMOTopIO extends Bundle {
  val bismo_busy = Output(Bool())
}

class BISMOTileLinkModule(params: BISMOTileLinkParams)(implicit p: Parameters)
    extends LazyModule {

  val dataWidthAXI = 64

  // dbb TL
  val dbb_tl_node = TLIdentityNode()

  // dbb AXI
  val dbb_axi_node = AXI4MasterNode(
    Seq(
      AXI4MasterPortParameters(
        masters =
          Seq(AXI4MasterParameters(name = "BISMO DBB", id = IdRange(0, 256)))
      )
    )
  )

  // TL <-> AXI
  (dbb_tl_node
    := TLBuffer()
    := TLWidthWidget(dataWidthAXI / 8)
    := AXI4ToTL()
    := AXI4UserYanker(capMaxFlight = Some(16))
    := AXI4Fragmenter()
    := AXI4IdIndexer(idBits = 6)
    := AXI4Buffer()
    := dbb_axi_node)

  // TODO what values should be in here?
  // Simple device for regmap
  val device = new SimpleDevice("bismo-regmap", Seq("bismo"))
  // Register mapping node
  val node = TLRegisterNode(
    address = Seq(AddressSet(params.address, 0xffff)),
    device = device,
    beatBytes = 8,
    concurrency = 1
  )

  val numMemPorts = 1
  // Define inputs and outputs needed to communicate with the accelerator
  // BISMO params
  val bismoInitParams = new BitSerialMatMulParams(
    dpaDimLHS = 2,
    dpaDimRHS = 2,
    dpaDimCommon = 128,
    lhsEntriesPerMem = 128,
    rhsEntriesPerMem = 128,
    mrp = PYNQZ1Params.toMemReqParams()
  )

  lazy val module = new LazyModuleImp(this) {
    // Registers used

    // TODO move all these regs to an external bundle for less mess
    val cc_enable = Reg(Bool())

    // Perf
    val fetch_sel = Reg(UInt(log2Up(4).W))
    val exec_sel = Reg(UInt(log2Up(4).W))
    val res_sel = Reg(UInt(log2Up(4).W))

    // Fetch stage
    val fetch_enable = Reg(Bool())
    val fetch_op_valid = Reg(Bool())
    val lhs_l2_per_matrix = Reg(UInt(32.W))
    val rhs_l2_per_matrix = Reg(UInt(32.W))
    val z_l2_per_matrix = Reg(UInt(32.W))


    val fetch_op_count = Reg(UInt(32.W))

    val fetch_runcfg_valid = Reg(Bool())
    val fetch_runcfg_bram_addr_base = Reg(
      UInt(bismoInitParams.fetchStageParams.numAddrBits.W)
    )
    val fetch_runcfg_bram_id_range = Reg(
      UInt(bismoInitParams.fetchStageParams.getIDBits.W)
    )
    val fetch_runcfg_bram_id_start = Reg(
      UInt(bismoInitParams.fetchStageParams.getIDBits.W)
    )
    val fetch_runcfg_dram_base = Reg(UInt(64.W))
    val fetch_runcfg_dram_block_count = Reg(UInt(64.W))
    val fetch_runcfg_dram_block_offset_bytes = Reg(UInt(64.W))
    val fetch_runcfg_dram_block_size_byes = Reg(UInt(64.W))
    val fetch_runcfg_tiles_per_row = Reg(UInt(16.W))

    // Exec stage
    val exec_enable = Reg(Bool())

    // val exec_op = Wire(DecoupledIO(new ControllerCmd(2, 2)))
    val exec_op_valid = Reg(Bool())
    val exec_op_opcode = Reg(UInt(2.W))
    val exec_op_token_channel = Reg(UInt(log2Ceil(2).W))

    val exec_op_count = Reg(UInt(32.W))
    val exec_runcfg_valid = Reg(Bool())
    val exec_runcfg_clear_before_first_accumulation = Reg(Bool())
    val exec_runcfg_rhsOffset = Reg(UInt(32.W))
    val exec_runcfg_lhsOffset = Reg(UInt(32.W))
    val exec_runcfg_negate = Reg(Bool())
    val exec_runcfg_numTiles = Reg(UInt(32.W))
    val exec_runcfg_writeEn = Reg(Bool())
    val exec_runcfg_writeAddr = Reg(
      UInt(log2Ceil(bismoInitParams.execStageParams.resEntriesPerMem).W)
    )
    val exec_runcfg_shiftAmount = Reg(
      UInt(
        (bismoInitParams.execStageParams.dpaParams.dpuParams.maxShiftSteps + 1).W
      )
    )

    // Result stage
    val result_enable = Reg(Bool())

    val result_op_valid = Reg(Bool())
    val result_op_opcode = Reg(UInt(2.W))
    val result_op_token_channel = Reg(UInt(log2Ceil(2).W))

    val result_op_count = Reg(UInt(32.W))

    val result_runcfg_valid = Reg(Bool())
    val result_runcfg_dram_base = Reg(UInt(64.W))
    val result_runcfg_dram_skip = Reg(UInt(64.W))
    val result_runcfg_resmem_addr = Reg(
      UInt(log2Up(bismoInitParams.resultStageParams.resEntriesPerMem).W)
    )
    val result_runcfg_waitComplete = Reg(Bool())
    val result_runcfg_waitCompleteBytes = Reg(UInt(32.W))

    // Perf variables
    val cc = Reg(UInt(32.W))

    val prf_fetch_count = RegInit(0.U(32.W))
    val prf_exec_count = RegInit(0.U(32.W))
    val prf_res_count = RegInit(0.U(32.W))

    // Hardware config
    val hardware_config = Wire(new BitSerialMatMulHWCfg(32))

    // Tilelink node
    val (dbb, _) = dbb_axi_node.out(0)

    // Instansiate BISMO
    val emuP = TesterWrapperParams
    val impl = Module(new BitSerialMatMulAccel(bismoInitParams, emuP))

    // TODO make a val impl.io.memPort(0) for better readability

    // Wire everything up

    // Read address axi
    dbb.ar.valid := impl.io.memPort(0).memRdReq.valid
    impl.io.memPort(0).memRdReq.ready := dbb.ar.ready
    dbb.ar.bits.addr := impl.io.memPort(0).memRdReq.bits.addr
    dbb.ar.bits.size := 3.U
    dbb.ar.bits.len := 0.U
    dbb.ar.bits.id := impl.io.memPort(0).memRdReq.bits.channelID

    dbb.r.ready := impl.io.memPort(0).memRdRsp.ready
    impl.io.memPort(0).memRdRsp.valid := dbb.r.valid
    impl.io.memPort(0).memRdRsp.bits.readData := dbb.r.bits.data
    impl.io.memPort(0).memRdRsp.bits.isLast := dbb.r.bits.last
    impl.io.memPort(0).memRdRsp.bits.channelID := dbb.r.bits.id
    impl.io.memPort(0).memRdRsp.bits.isWrite := false.B
    impl.io.memPort(0).memRdRsp.bits.metaData := 0.U

    // MemWrReq
    impl.io.memPort(0).memWrReq.ready := dbb.aw.ready
    dbb.aw.valid := impl.io.memPort(0).memWrReq.valid
    dbb.aw.bits.addr := impl.io.memPort(0).memWrReq.bits.addr
    dbb.aw.bits.id := impl.io.memPort(0).memWrReq.bits.channelID
    dbb.aw.bits.len := 0.U
    dbb.aw.bits.size := 3.U

    // MemWrDat
    impl.io.memPort(0).memWrDat.ready := dbb.w.ready
    dbb.w.valid := impl.io.memPort(0).memWrDat.valid
    dbb.w.bits.data := impl.io.memPort(0).memWrDat.bits

    // MemWrRsp
    dbb.b.ready := impl.io.memPort(0).memWrRsp.ready
    impl.io.memPort(0).memWrRsp.valid := dbb.b.valid
    impl.io.memPort(0).memWrRsp.bits.isWrite := true.B
    impl.io.memPort(0).memWrRsp.bits.isLast := false.B
    impl.io.memPort(0).memWrRsp.bits.channelID := dbb.b.bits.id
    impl.io.memPort(0).memWrRsp.bits.metaData := 0.U
    impl.io.memPort(0).memWrRsp.bits.readData := dbb.b.bits.resp

    // Fetch stage
    impl.io.fetch_enable := fetch_enable
    // Fetch OP
    impl.io.fetch_op.valid := fetch_op_valid
    impl.io.fetch_op.bits.lhs_l2_per_matrix := lhs_l2_per_matrix
    impl.io.fetch_op.bits.rhs_l2_per_matrix := rhs_l2_per_matrix
    impl.io.fetch_op.bits.z_l2_per_matrix := z_l2_per_matrix

    // Fetch OP count
    fetch_op_count := impl.io.fetch_op_count

    impl.io.fetch_runcfg.valid := fetch_runcfg_valid
    impl.io.fetch_runcfg.bits.dram_block_count := fetch_runcfg_dram_block_count
    impl.io.fetch_runcfg.bits.dram_block_offset_bytes := fetch_runcfg_dram_block_offset_bytes
    impl.io.fetch_runcfg.bits.dram_block_size_bytes := fetch_runcfg_dram_block_size_byes
    impl.io.fetch_runcfg.bits.tiles_per_row := fetch_runcfg_tiles_per_row

    // Exec stage
    impl.io.exec_enable := exec_enable

    // Exec stage - exec OP
    impl.io.exec_op.valid := exec_op_valid

    exec_op_count := impl.io.exec_op_count

    impl.io.exec_runcfg.valid := exec_runcfg_valid
    impl.io.exec_runcfg.bits.negate := exec_runcfg_negate
    impl.io.exec_runcfg.bits.numTiles := exec_runcfg_numTiles
    impl.io.exec_runcfg.bits.shiftAmount := exec_runcfg_shiftAmount

    // Result Stage
    impl.io.result_enable := result_enable
    // Result OP
    impl.io.result_op.valid := result_op_valid

    result_op_count := impl.io.result_op_count

    impl.io.result_runcfg.valid := result_runcfg_valid
    impl.io.result_runcfg.bits.dram_base := result_runcfg_dram_base
    impl.io.result_runcfg.bits.dram_skip := result_runcfg_dram_skip

    // Perf
    impl.io.perf.cc_enable := cc_enable

    impl.io.perf.prf_fetch.sel := fetch_sel


    prf_exec_count := impl.io.perf.prf_exec.count
    impl.io.perf.prf_exec.sel := exec_sel

    prf_res_count := impl.io.perf.prf_res.count
    impl.io.perf.prf_res.sel := res_sel

    // Hardware config
    hardware_config <> impl.io.hw

    // Maps it with regmap, the control signals can be accessed on (address + 2000)
    // TODO tidy this up
    node.regmap(
      0x00 -> Seq(
        RegField.w(1, fetch_enable)
      ),
      // Fetch OP
      0x04 -> Seq(
        RegField.w(2, fetch_op_opcode)
      ),
      0x08 -> Seq(
        RegField.w(log2Ceil(math.max(1, 1)), fetch_op_token_channel)
      ),
      0x0c -> Seq(
        RegField.w(1, fetch_op_valid)
      ),
      // Fetch runcfg
      0x0e -> Seq(
        RegField.w(1, fetch_runcfg_valid)
      ),
      0x10 -> Seq(
        RegField.w(64, fetch_runcfg_dram_base)
      ),
      0x18 -> Seq(
        RegField.w(32, fetch_runcfg_dram_block_size_byes)
      ),
      0x1c -> Seq(
        RegField.w(32, fetch_runcfg_dram_block_offset_bytes)
      ),
      0x20 -> Seq(
        RegField.w(16, fetch_runcfg_dram_block_count)
      ),
      0x28 -> Seq(
        RegField.w(64, fetch_runcfg_tiles_per_row)
      ),
      0x30 -> Seq(
        RegField.w(64, fetch_runcfg_bram_addr_base)
      ),
      0x38 -> Seq(
        RegField.w(64, fetch_runcfg_bram_id_start)
      ),
      0x40 -> Seq(
        RegField.w(64, fetch_runcfg_bram_id_range)
      ),
      // Fetch OP count
      0x48 -> Seq(
        RegField.r(32, fetch_op_count)
      ),
      // Exec controller
      0x4c -> Seq(
        RegField.w(1, exec_enable)
      ),
      // Exec opcode
      0x50 -> Seq(
        RegField.w(1, exec_op_valid)
      ),
      0x54 -> Seq(
        RegField.w(2, exec_op_opcode)
      ),
      0x58 -> Seq(
        RegField.w(log2Ceil(2), exec_op_token_channel)
      ),
      // Exec runcfg
      0x5c -> Seq(
        RegField.w(1, exec_runcfg_valid)
      ),
      0x60 -> Seq(
        RegField.w(1, exec_runcfg_clear_before_first_accumulation)
      ),
      0x68 -> Seq(
        RegField.w(32, exec_runcfg_rhsOffset)
      ),
      0x70 -> Seq(
        RegField.w(32, exec_runcfg_lhsOffset)
      ),
      0x78 -> Seq(
        RegField.w(1, exec_runcfg_negate)
      ),
      0x7c -> Seq(
        RegField.w(32, exec_runcfg_numTiles)
      ),
      0x84 -> Seq(
        RegField.w(1, exec_runcfg_writeEn)
      ),
      0x88 -> Seq(
        RegField.w(
          log2Ceil(bismoInitParams.execStageParams.resEntriesPerMem),
          exec_runcfg_writeAddr
        )
      ),
      0x8c -> Seq(
        RegField.w(
          bismoInitParams.execStageParams.dpaParams.dpuParams.maxShiftSteps + 1,
          exec_runcfg_shiftAmount
        )
      ),
      0x90 -> Seq(
        RegField.r(32, exec_op_count)
      ),
      0x98 -> Seq(
        RegField.w(1, result_enable)
      ),
      // Result OP
      0x9a -> Seq(
        RegField.w(1, result_op_valid)
      ),
      0x9c -> Seq(
        RegField.w(2, result_op_opcode)
      ),
      0x9e -> Seq(
        RegField.w(log2Ceil(1), result_op_token_channel)
      ),
      // Result runcfg
      0xa0 -> Seq(
        RegField.w(1, result_runcfg_valid)
      ),
      0xa8 -> Seq(
        RegField.w(64, result_runcfg_dram_base)
      ),
      0xb0 -> Seq(
        RegField.w(64, result_runcfg_dram_skip)
      ),
      0xb8 -> Seq(
        RegField.w(
          log2Up(bismoInitParams.resultStageParams.resEntriesPerMem),
          result_runcfg_resmem_addr
        )
      ),
      0xc0 -> Seq(
        RegField.w(1, result_runcfg_waitComplete)
      ),
      0xc8 -> Seq(
        RegField.w(32, result_runcfg_waitCompleteBytes)
      ),
      0xd0 -> Seq(
        RegField.r(32, result_op_count)
      ),
      0xd8 -> Seq(
        RegField.r(32, impl.io.hw.readChanWidth)
      ),
      0xe0 -> Seq(
        RegField.r(32, impl.io.hw.writeChanWidth)
      ),
      0xe8 -> Seq(
        RegField.r(32, impl.io.hw.dpaDimLHS)
      ),
      0xf0 -> Seq(
        RegField.r(32, impl.io.hw.dpaDimRHS)
      ),
      0xf8 -> Seq(
        RegField.r(32, impl.io.hw.dpaDimCommon)
      ),
      0x100 -> Seq(
        RegField.r(32, impl.io.hw.lhsEntriesPerMem)
      ),
      0x108 -> Seq(
        RegField.r(32, impl.io.hw.rhsEntriesPerMem)
      ),
      0x110 -> Seq(
        RegField.r(32, impl.io.hw.accWidth)
      ),
      0x118 -> Seq(
        RegField.r(32, impl.io.hw.maxShiftSteps)
      ),
      0x120 -> Seq(
        RegField.r(32, impl.io.hw.cmdQueueEntries)
      ),
      0x128 -> Seq(
        RegField.r(1, impl.io.fetch_runcfg.ready)
      ),
      // TODO this is too much of a gap, fix later
      0x130 -> Seq(
        RegField.r(1, impl.io.exec_runcfg.ready)
      ),
      0x134 -> Seq(
        RegField.r(1, impl.io.result_runcfg.ready)
      ),
      0x138 -> Seq(
        RegField.r(1, impl.io.fetch_op.ready)
      ),
      0x13c -> Seq(
        RegField.r(1, impl.io.exec_op.ready)
      ),
      0x140 -> Seq(
        RegField.r(1, impl.io.result_op.ready)
      ),
      0x144 -> Seq(
        RegField.r(32, impl.io.perf.cc)
      ),
      0x14c -> Seq(
        RegField.w(1, cc_enable)
      ),
      // perf fetch
      0x150 -> Seq(
        RegField.r(32, impl.io.perf.prf_fetch.count)
      ),
      0x158 -> Seq(
        RegField.w(2, fetch_sel)
      ),
      // perf exec
      0x15c -> Seq(
        RegField.r(32, impl.io.perf.prf_exec.count)
      ),
      0x164 -> Seq(
        RegField.w(2, exec_sel)
      ),
      // perf result
      0x168 -> Seq(
        RegField.r(32, impl.io.perf.prf_res.count)
      ),
      0x170 -> Seq(
        RegField.w(2, res_sel)
      )
    )
  }
}

trait CanHavePeripheryBISMO { this: BaseSubsystem =>
  val portName = "bismo"
  p(BISMOKey).map { params =>
    val bismo = LazyModule(new BISMOTileLinkModule(params))
    fbus.fromMaster(name = Some("bismo_dbb"), buffer = BufferParams.default) {
      TLBuffer.chainNode(p(BISMOFrontBusExtraBuffers))
    } := bismo.dbb_tl_node
    pbus.toVariableWidthSlave(Some(portName)) { bismo.node }
  }
}

class WithBISMO(useAXI4: Boolean = false, useBlackBox: Boolean = false)
    extends Config((site, here, up) => { case BISMOKey =>
      Some(BISMOTileLinkParams())
    })
