package bismo

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import chisel3.experimental.BundleLiterals._
import BISMOTestHelper._
import fpgatidbits.dma.MemReqParams

class TestExecInstructionGenerator()
    extends AnyFreeSpec
    with ChiselScalatestTester {
  val dpaDimCommon = 128
//   val dpaDimLHS = 8
//   val dpaDimRHS = 8
  val lhsEntriesPerMem = 128
  val rhsEntriesPerMem = 128
  val readChanWidth = 64

  // DPA params

  // Dimensions
  val m = 2
  val n = 2

  // DPU params

  // width of accumulator register
  val accWidth = 32
  // maximum number of shift steps
  val maxShiftSteps = 16

  // pcParams
  val numInputBits = 64

  // ExecStageParams
  val lhsTileMem = 1024
  val rhsTileMem = 1024
  val tileMemAddrUnit = 1

  val pcParams = new PopCountUnitParams(numInputBits)

  val dpuParams = new DotProductUnitParams(pcParams, accWidth, maxShiftSteps)

  val dpaParams = new DotProductArrayParams(dpuParams, m, n)

  val execStageParams =
    new ExecStageParams(
      dpaParams,
      lhsTileMem,
      rhsTileMem,
      tileMemAddrUnit
    )
  "Test ExecInstructionGenerator" in {
    test(
      new ExecInstructionGenerator(
        execStageParams,
        dpaDimCommon,
        readChanWidth,
        lhsEntriesPerMem,
        rhsEntriesPerMem
      )
    ).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      val lhs_l1_per_l2 = 0x04
      val rhs_l1_per_l2 = 0x04

      val lhs_l2_per_matrix = 0x04
      val rhs_l2_per_matrix = 0x04
      val z_l2_per_matrix = 0x08

      val bram_regions = 2
      val resmem_regions = 2

      val lhs_l0_per_bram = lhsEntriesPerMem / bram_regions
      val rhs_l0_per_bram = rhsEntriesPerMem / bram_regions

      val exec_to_fetch_width_ratio = dpaDimCommon / readChanWidth

      val negate = 0
      val numTiles = 64
      val shiftAmount = 0

      c.clock.setTimeout(0)

      def check_golden() = {
        var current_bram_region = 0
        var current_resmem_region = 0
        for (lhs_l2 <- 0 until lhs_l2_per_matrix) {
          for (rhs_l2 <- 0 until rhs_l2_per_matrix) {
            for (z_l2 <- 0 until z_l2_per_matrix) {
              for (lhs_l1 <- 0 until lhs_l1_per_l2) {
                for (rhs_l1 <- 0 until rhs_l1_per_l2) {
                  val lhsOffset =
                    current_bram_region * lhs_l0_per_bram + lhs_l1 * numTiles * exec_to_fetch_width_ratio

                  val rhsOffset =
                    current_bram_region * rhs_l0_per_bram + rhs_l1 * numTiles * exec_to_fetch_width_ratio

                  val writeEn = if (z_l2 == z_l2_per_matrix - 1) 1 else 0
                  val doClear = if (z_l2 == 0) 1 else 0

                  c.io.out.negate.expect(negate)
                  c.io.out.numTiles.expect(numTiles)
                  c.io.out.shiftAmount.expect(shiftAmount)
                  c.io.out.lhsOffset.expect(lhsOffset)
                  c.io.out.rhsOffset.expect(rhsOffset)
                  c.io.out.writeAddr.expect(current_resmem_region)
                  c.io.out.writeEn.expect(writeEn)
                  c.io.out.clear_before_first_accumulation.expect(doClear)
                  if (z_l2 == z_l2_per_matrix - 1) {
                    if (current_resmem_region < resmem_regions - 1) {
                      current_resmem_region = current_resmem_region + 1
                    } else {
                      current_resmem_region = 0
                    }
                  }

                  c.clock.step(1)
                }
              }
              if (current_bram_region < bram_regions - 1) {
                current_bram_region = current_bram_region + 1
              } else {
                current_bram_region = 0
              }
            }
          }
        }
      }
      c.io.in.ready.expect(true)

      c.io.in.bits.lhs_l1_per_l2.poke(lhs_l1_per_l2)
      c.io.in.bits.rhs_l1_per_l2.poke(rhs_l1_per_l2)

      c.io.in.bits.lhs_l2_per_matrix.poke(lhs_l2_per_matrix)
      c.io.in.bits.rhs_l2_per_matrix.poke(rhs_l2_per_matrix)
      c.io.in.bits.z_l2_per_matrix.poke(z_l2_per_matrix)

      c.io.in.bits.negate.poke(negate)
      c.io.in.bits.numTiles.poke(numTiles)
      c.io.in.bits.shiftAmount.poke(shiftAmount)

      c.io.in.valid.poke(true)
      
      check_golden()
    }
  }
}
