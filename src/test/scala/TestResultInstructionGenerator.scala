package bismo

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import chisel3.experimental.BundleLiterals._
import BISMOTestHelper._
import fpgatidbits.dma.MemReqParams

class TestResultInstructionGenerator()
    extends AnyFreeSpec
    with ChiselScalatestTester {
  val dpaDimCommon = 128
  val dpaDimLHS = 8
  val dpaDimRHS = 8
  val lhsEntriesPerMem = 128
  val rhsEntriesPerMem = 128
  val readChanWidth = 64

  // Fetch stage params
  val accWidth = 32 // accumulator width in bits
  // read latency for result memory
  val resMemReadLatency = 0
  // Fetch stage memory request params
  val addrWidth = 64
  val dataWidth = 64
  val idWidth = 64
  val metaDataWidth = 64

  val resultStageParams =
    new ResultStageParams(
      accWidth,
      dpaDimRHS,
      dpaDimLHS,
      new MemReqParams(addrWidth, dataWidth, idWidth, metaDataWidth),
      resMemReadLatency
    )
  "Test ResultInstructionGenerator" in {
    test(
      new ResultInstructionGenerator(
        resultStageParams,
        lhsEntriesPerMem,
        dpaDimCommon,
        dpaDimLHS,
        dpaDimRHS
      )
    ).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      val dram_skip = 0x20
      val dram_base = 0x00
      val nrows_a = 0x01
      val lhs_l1_per_l2 = 0x01
      val rhs_l1_per_l2 = 0x01

      val lhs_l2_per_matrix = 0x04
      val rhs_l2_per_matrix = 0x04
      val z_l2_per_matrix = 0x08

      val bram_regions = 2
      def get_result_tile_ptr(lhs_tile: Int, rhs_tile: Int): Int = {
        val lhs_ind = dpaDimLHS * lhs_tile
        val rhs_ind = dpaDimRHS * rhs_tile
        val ind = rhs_ind * nrows_a + lhs_ind

        // TODO how to handle the size aspect, maybe signal from SW?
        val res_type_size = 4
        return dram_base + (ind * 4)
      }

      def check_golden() = {
        var current_bram_region = 0
        for (lhs_l2 <- 0 until lhs_l2_per_matrix) {
          for (rhs_l2 <- 0 until rhs_l2_per_matrix) {
            for (lhs_l1 <- 0 until lhs_l1_per_l2) {
              for (rhs_l1 <- 0 until rhs_l1_per_l2) {
                val lhs_tile = lhs_l1_per_l2 * lhs_l2 + lhs_l1
                val rhs_tile = rhs_l1_per_l2 * rhs_l2 + rhs_l1
                c.io.out.ready.poke(true)
                c.io.out.bits.resmem_addr.expect(current_bram_region)
                c.io.out.bits.dram_base
                  .expect(get_result_tile_ptr(lhs_tile, rhs_tile))
                c.io.out.bits.dram_skip.expect(dram_skip)
                c.io.out.bits.waitComplete.expect(0)
                c.io.out.bits.waitCompleteBytes.expect(0)

                if (current_bram_region < bram_regions - 1) {
                  current_bram_region = current_bram_region + 1
                } else {
                  current_bram_region = 0
                }
                c.clock.step(1)
              }
            }
          }
        }
      }
      c.io.in.bits.dram_skip.poke(dram_skip)
      c.io.in.bits.dram_base.poke(dram_base)

      c.io.in.bits.nrows_a.poke(nrows_a)

      c.io.in.bits.lhs_l1_per_l2.poke(lhs_l1_per_l2)
      c.io.in.bits.rhs_l1_per_l2.poke(rhs_l1_per_l2)

      c.io.in.bits.lhs_l2_per_matrix.poke(lhs_l2_per_matrix)
      c.io.in.bits.rhs_l2_per_matrix.poke(rhs_l2_per_matrix)
      c.io.in.bits.z_l2_per_matrix.poke(z_l2_per_matrix)

      c.io.in.valid.poke(true)

      check_golden()
    }
  }
}
