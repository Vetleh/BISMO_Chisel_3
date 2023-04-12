package bismo

import chisel3._
import chisel3.util._

class ResultInstructionGeneratorIn() extends Bundle {
  // TODO look into size of all variables wires
  val nrows_a = UInt(32.W)

  val lhs_l1_per_l2 = UInt(32.W)
  val rhs_l1_per_l2 = UInt(32.W)

  val lhs_l2_per_matrix = UInt(32.W)
  val rhs_l2_per_matrix = UInt(32.W)
  val z_l2_per_matrix = UInt(32.W)

  val dram_base = UInt(64.W)
  val dram_skip = UInt(64.W)
}

class ResultInstructionGeneratorIO(myP: ResultStageParams) extends Bundle {
  val in = Flipped(Decoupled(new ResultInstructionGeneratorIn()))
  val out = Decoupled(new ResultStageCtrlIO(myP))
}

class ResultInstructionGenerator(
    myP: ResultStageParams,
    lhsEntriesPerMem: Int,
    dpaDimCommon: Int,
    dpaDimLHS: Int,
    dpaDimRHS: Int
) extends Module {

  val io = IO(new ResultInstructionGeneratorIO(myP))
  def get_result_tile_ptr(lhs_tile: UInt, rhs_tile: UInt): UInt = {
    // val lhs_ind = dpaDimLHS.U * lhs_tile
    // val rhs_ind = dpaDimRHS.U * rhs_tile
    // May want result type to be something else than 32
    return io.in.bits.dram_base + (dpaDimRHS.U * rhs_tile * io.in.bits.nrows_a + dpaDimLHS.U * lhs_tile) * 4.U
  }
  // Calculate size based on dpu
  val iterations = 32.U
  val counter_2 = RegInit(UInt(1.W), 0.U)
  val current_resmem_region = RegInit(UInt(64.W), 0.U)

  val lhs_l2 = RegInit(UInt(32.W), 0.U)
  val rhs_l2 = RegInit(UInt(32.W), 0.U)
  val lhs_l1 = RegInit(UInt(32.W), 0.U)
  val rhs_l1 = RegInit(UInt(32.W), 0.U)

  val bram_regions = 2 // Res Exec tokens, why is this a correlation?
  val lhs_l0_per_bram = lhsEntriesPerMem / bram_regions
  val rhs_l0_per_bram = lhsEntriesPerMem / bram_regions

  // Need 3 nested for loops
  val counter = RegInit(UInt(32.W), 0.U)

  val isHigh = RegInit(Bool(), false.B)

  // TODO total iters are wrong
  val total_iters =
    io.in.bits.lhs_l2_per_matrix * io.in.bits.rhs_l2_per_matrix * io.in.bits.lhs_l1_per_l2 * io.in.bits.rhs_l1_per_l2

  io.out.bits.waitCompleteBytes := 0.U
  io.out.bits.waitComplete := false.B

  when(isHigh && io.in.valid && io.out.ready) {
    io.out.bits.dram_skip := 0.U
    io.out.bits.dram_base := 0.U
    io.out.bits.resmem_addr := 0.U
    io.out.bits.waitCompleteBytes := 0.U
    io.out.bits.waitComplete := false.B
    io.in.ready := false.B

    io.out.valid := false.B
    isHigh := false.B
  }.otherwise {
    when(counter < total_iters && io.in.valid && io.out.ready) {
      io.in.ready := false.B
      io.out.valid := true.B
      io.out.bits.dram_skip := io.in.bits.dram_skip
      val lhs_tile = io.in.bits.lhs_l1_per_l2 * lhs_l2 + lhs_l1;
      val rhs_tile = io.in.bits.rhs_l1_per_l2 * rhs_l2 + rhs_l1;
      io.out.bits.dram_base := get_result_tile_ptr(lhs_tile, rhs_tile)
      io.out.bits.resmem_addr := current_resmem_region
      isHigh := true.B
      // Increment current BRAM region
      current_resmem_region := Mux(
        current_resmem_region < (bram_regions - 1).U,
        current_resmem_region + 1.U,
        0.U
      )
      counter := counter + 1.U
      rhs_l1 := rhs_l1 + 1.U
      when(rhs_l1 === io.in.bits.rhs_l1_per_l2 - 1.U) {
        rhs_l1 := 0.U
        lhs_l1 := lhs_l1 + 1.U
        when(lhs_l1 === io.in.bits.lhs_l1_per_l2 - 1.U) {
          lhs_l1 := 0.U
          rhs_l2 := rhs_l2 + 1.U
          when(rhs_l2 === io.in.bits.rhs_l2_per_matrix - 1.U) {
            rhs_l2 := 0.U
            lhs_l2 := lhs_l2 + 1.U
            when(lhs_l2 === io.in.bits.lhs_l2_per_matrix - 1.U) {
              lhs_l2 := 0.U
            }
          }
        }
      }

    }.elsewhen(counter === total_iters && io.in.valid && io.out.ready) {
      io.in.ready := false.B
      io.out.valid := true.B
      io.out.bits.dram_skip := 0.U
      io.out.bits.dram_base := 0.U
      io.out.bits.resmem_addr := 0.U
      io.out.bits.waitCompleteBytes := 0x10.U
      io.out.bits.waitComplete := true.B
      isHigh := true.B
      counter := counter + 1.U
    }.otherwise {
      io.in.ready := true.B
      io.out.valid := false.B
      io.out.bits.dram_skip := 0.U
      io.out.bits.dram_base := 0.U
      io.out.bits.resmem_addr := 0.U
      isHigh := false.B
    }
  }
  // Wire static fields

  // These seems to be used to track waiting, can be a part of the initial wait, should be set at end
  // Generate instructions and pass it to the Fetch stage controller
}
