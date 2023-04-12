package bismo

import chisel3._
import chisel3.util._

class ExecInstructionGeneratorIn(myP: ExecStageParams) extends Bundle {
  val lhs_l1_per_l2 = UInt(32.W)
  val rhs_l1_per_l2 = UInt(32.W)

  val lhs_l2_per_matrix = UInt(32.W)
  val rhs_l2_per_matrix = UInt(32.W)
  val z_l2_per_matrix = UInt(32.W)

  val numTiles = UInt(32.W) // num of L0 tiles to execute

  // how much left shift to use
  val shiftAmount =
    UInt(log2Up(myP.dpaParams.dpuParams.maxShiftSteps + 1).W)

  // negate during accumulation
  val negate = Bool()
}

class ExecInstructionGeneratorIO(myP: ExecStageParams) extends Bundle {
  val in = Flipped(Decoupled(new ExecInstructionGeneratorIn(myP)))
  val out = Decoupled(new ExecStageCtrlIO(myP))
}

class ExecInstructionGenerator(
    myP: ExecStageParams,
    dpaDimCommon: Int,
    readChanWidth: Int,
    lhsEntriesPerMem: Int,
    rhsEntriesPerMem: Int
) extends Module {
  val io = IO(new ExecInstructionGeneratorIO(myP))

  val current_resmem_region = RegInit(UInt(64.W), 0.U)
  val current_bram_region = RegInit(UInt(64.W), 0.U)
  val bram_regions = 2 // Res Exec tokens, why is this a correlation?

  val lhs_l0_per_bram = lhsEntriesPerMem / bram_regions
  val rhs_l0_per_bram = rhsEntriesPerMem / bram_regions

  val exec_to_fetch_width_ratio = dpaDimCommon / readChanWidth

  // Loop counters
  val counter = RegInit(UInt(32.W), 0.U)
  val z_l2 = RegInit(UInt(32.W), 0.U)
  val lhs_l1 = RegInit(UInt(32.W), 0.U)
  val rhs_l1 = RegInit(UInt(32.W), 0.U)

  val total_iters =
    io.in.bits.lhs_l2_per_matrix * io.in.bits.rhs_l2_per_matrix * io.in.bits.z_l2_per_matrix * io.in.bits.lhs_l1_per_l2 * io.in.bits.rhs_l1_per_l2

  io.in.ready := false.B
  io.out.valid := false.B
  
  when(counter < total_iters && io.in.valid && io.out.ready) {
    io.out.valid := true.B
    io.out.bits.lhsOffset := current_bram_region * lhs_l0_per_bram.U + lhs_l1 * io.in.bits.numTiles * exec_to_fetch_width_ratio.U
    io.out.bits.rhsOffset := current_bram_region * rhs_l0_per_bram.U + rhs_l1 * io.in.bits.numTiles * exec_to_fetch_width_ratio.U
    io.out.bits.clear_before_first_accumulation := Mux(z_l2 === 0.U, 1.U, 0.U)
    io.out.bits.writeEn := Mux(z_l2 === io.in.bits.z_l2_per_matrix - 1.U, 1.U, 0.U)
    io.out.bits.writeAddr := current_resmem_region
    // Current result memory BRAM logic
    when(z_l2 === io.in.bits.z_l2_per_matrix - 1.U) {
      // Increment current BRAM region
      current_resmem_region := Mux(
        current_resmem_region < (bram_regions - 1).U,
        current_resmem_region + 1.U,
        0.U
      )
    }

    // Loop logic
    rhs_l1 := rhs_l1 + 1.U
    when(rhs_l1 === io.in.bits.rhs_l1_per_l2 - 1.U) {
      rhs_l1 := 0.U
      lhs_l1 := lhs_l1 + 1.U
      when(lhs_l1 === io.in.bits.lhs_l1_per_l2 - 1.U) {
        lhs_l1 := 0.U
        z_l2 := z_l2 + 1.U
        when(z_l2 === io.in.bits.z_l2_per_matrix - 1.U) {
          z_l2 := 0.U
        }
        // Increment BRAM region
        current_bram_region := Mux(
          current_bram_region < (bram_regions - 1).U,
          current_bram_region + 1.U,
          0.U
        )
      }
    }
  }.otherwise {
    io.in.ready := true.B
    io.out.bits.lhsOffset := 0.U
    io.out.bits.rhsOffset := 0.U
    io.out.bits.writeAddr := 0.U
    io.out.bits.writeEn := 0.U
    io.out.bits.clear_before_first_accumulation := 0.U
  }

  // Static signals
  io.out.bits.numTiles := io.in.bits.numTiles
  // TODO what should these be?
  io.out.bits.negate := io.in.bits.negate
  io.out.bits.shiftAmount := io.in.bits.shiftAmount
}
