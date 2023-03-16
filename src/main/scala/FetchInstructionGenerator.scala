package bismo

import chisel3._
import chisel3.util._

class FetchInstructionGeneratorIO(myP: FetchStageParams) extends Bundle {
  val in = Flipped(Decoupled(new Bundle {
    // DRAM fetch config
    // base address for all fetch groups
    val dram_base_lhs = UInt(64.W)
    val dram_base_rhs = UInt(64.W)
    val dram_block_offset_bytes = UInt(32.W)
    val dram_block_size_bytes = UInt(32.W)
    val dram_block_count = UInt(32.W)

    val z_l2_per_matrix = UInt(64.W)
    val lhs_l2_per_matrix = UInt(64.W)
    val rhs_l2_per_matrix = UInt(64.W)

    val lhs_bytes_per_l2 = UInt(64.W)
    val rhs_bytes_per_l2 = UInt(64.W)

    val tiles_per_row = UInt(16.W)
  }))

  val out = Output(new FetchStageCtrlIO(myP))
}

class FetchInstructionGenerator(
    myP: FetchStageParams,
    dpaDimCommon: Int,
    dpaDimLHS: Int,
    dpaDimRHS: Int,
    lhsEntriesPerMem: Int,
    rhsEntriesPerMem: Int,
    readChanWidth: Int
) extends Module {
  val io = IO(new FetchInstructionGeneratorIO(myP))

  val lhs_bytes_per_l0 = dpaDimLHS * dpaDimRHS / 8;
  val rhs_bytes_per_l0 = dpaDimLHS * dpaDimRHS / 8;

  val exec_to_fetch_width_ratio = dpaDimCommon / readChanWidth
  val bram_regions = 2 // Fetch Exec tokens, why is this a correlation?

  val lhs_l0_per_bram = lhsEntriesPerMem / bram_regions;
  val rhs_l0_per_bram = rhsEntriesPerMem / bram_regions;

  // Counters (Probably dosent need to be so wide)
  val lhs_l2 = RegInit(UInt(32.W), 0.U)
  val rhs_l2 = RegInit(UInt(32.W), 0.U)
  val z_l2 = RegInit(UInt(32.W), 0.U)

  // Counter for keeping track of amount of iterations total
  val counter = RegInit(UInt(32.W), 0.U)

  // keeps track on if the current fetch ins is for rhs or lhs
  val is_rhs = RegInit(UInt(1.W), 0.U)

  // Keeps track of the current bram region being operated on
  val current_bram_region = RegInit(UInt(16.W), 0.U)

  io.out.bram_addr_base := 0.U
  io.out.dram_base := 0.U
  io.out.bram_id_start := 0.U

  io.out.dram_block_size_bytes := io.in.bits.dram_block_size_bytes
  io.out.dram_block_offset_bytes := io.in.bits.dram_block_offset_bytes
  io.out.dram_block_count := io.in.bits.dram_block_count

  io.out.tiles_per_row := io.in.bits.tiles_per_row
  io.out.bram_id_range := 0.U

  val total_iters =
    io.in.bits.lhs_l2_per_matrix * io.in.bits.rhs_l2_per_matrix * io.in.bits.z_l2_per_matrix

  io.in.ready := false.B

  when(counter < total_iters && io.in.valid) {
    when(is_rhs === 0.U) {
      io.out.bram_addr_base := current_bram_region * lhs_l0_per_bram.U * exec_to_fetch_width_ratio.U
      io.out.bram_id_start := 0.U
      io.out.bram_id_range := (myP.numLHSMems - 1).U

      // TODO not all values are right
      io.out.dram_base := io.in.bits.dram_base_lhs + lhs_l2 * io.in.bits.z_l2_per_matrix * io.in.bits.lhs_bytes_per_l2 + z_l2 * io.in.bits.dram_block_size_bytes
    }.elsewhen(is_rhs === 1.U) {
      io.out.bram_addr_base := current_bram_region * rhs_l0_per_bram.U * exec_to_fetch_width_ratio.U
      io.out.bram_id_start := myP.numRHSMems.U
      io.out.bram_id_range := (myP.numRHSMems - 1).U

      io.out.dram_base := io.in.bits.dram_base_rhs + rhs_l2 * io.in.bits.z_l2_per_matrix * io.in.bits.rhs_bytes_per_l2 + z_l2 * io.in.bits.dram_block_size_bytes

      current_bram_region := Mux(
        current_bram_region < (bram_regions - 1).U,
        current_bram_region + 1.U,
        0.U
      )
      z_l2 := z_l2 + 1.U
      when(z_l2 === io.in.bits.z_l2_per_matrix - 1.U) {
        z_l2 := 0.U
        rhs_l2 := rhs_l2 + 1.U
        when(rhs_l2 === io.in.bits.rhs_l2_per_matrix - 1.U) {
          rhs_l2 := 0.U
          lhs_l2 := lhs_l2 + 1.U
        }
      }
    }
    is_rhs := is_rhs + 1.U
  }.otherwise {
    io.in.ready := true.B
    counter := 0.U
    lhs_l2 := 0.U
    rhs_l2 := 0.U
    z_l2 := 0.U
  }
}
