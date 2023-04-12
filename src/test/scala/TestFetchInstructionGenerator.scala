package bismo

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import chisel3.experimental.BundleLiterals._
import BISMOTestHelper._
import fpgatidbits.dma.MemReqParams

class TestFetchInstructionGenerator()
    extends AnyFreeSpec
    with ChiselScalatestTester {
  val dpaDimCommon = 128
  val dpaDimLHS = 8
  val dpaDimRHS = 8
  val lhsEntriesPerMem = 128
  val rhsEntriesPerMem = 128
  val readChanWidth = 64

  // Fetch stage params
  val numLHSMems = 2
  val numRHSMems = 2
  val numAddrBits = 64
  // Fetch stage memory request params
  val addrWidth = 64
  val dataWidth = 64
  val idWidth = 64
  val metaDataWidth = 64

  val fetchStageParams =
    new FetchStageParams(
      numLHSMems,
      numRHSMems,
      numAddrBits,
      new MemReqParams(addrWidth, dataWidth, idWidth, metaDataWidth)
    )
    
  "Test FetchInstructionGenerator" in {
    test(
      new FetchInstructionGenerator(
        fetchStageParams,
        dpaDimCommon,
        dpaDimLHS,
        dpaDimRHS,
        lhsEntriesPerMem,
        rhsEntriesPerMem,
        readChanWidth
      )
    ).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      val dram_base_lhs = 0xf9740
      val dram_base_rhs = 0x109740
      val dram_block_offset_bytes = 0x2000
      val dram_block_size_bytes = 0x400
      val tiles_per_row = 0x80
      val bram_id_range = 0x1
      val bram_id_start = 0x2
      val dram_block_count = 0x2
      val ncols_a = 1

      var current_bram_region = 0
      val bram_regions = 2

      val rhs_bytes_per_l2 = 2048
      val lhs_bytes_per_l2 = 2048

      val lhs_l0_per_bram = lhsEntriesPerMem / bram_regions
      val rhs_l0_per_bram = rhsEntriesPerMem / bram_regions
      val exec_to_fetch_width_ratio = dpaDimCommon / readChanWidth

      val comp_val = (ncols_a / dpaDimCommon) / lhs_l0_per_bram

      // Loop variables
      val lhs_l2_per_matrix = 4
      val rhs_l2_per_matrix = 4
      val z_l2_per_matrix = 8

      def check_golden() = {
        for (lhs_l2 <- 0 to lhs_l2_per_matrix - 1) {
          for (rhs_l2 <- 0 to rhs_l2_per_matrix - 1) {
            for (z_l2 <- 0 to z_l2_per_matrix - 1) {
              c.io.out.ready.poke(true)
              c.io.out.valid.expect(true)
              // LHS
              c.io.out.bits.dram_block_count.expect(dram_block_count)
              c.io.out.bits.dram_block_offset_bytes.expect(dram_block_offset_bytes)
              c.io.out.bits.dram_block_size_bytes.expect(dram_block_size_bytes)
              c.io.out.bits.dram_base.expect(
                dram_base_lhs + lhs_l2 * z_l2_per_matrix * lhs_bytes_per_l2 + z_l2 * dram_block_size_bytes
              )
              // fetch_base_lhs + lhs_l2*z_l2_per_matrix*lhs_bytes_per_l2 + z_l2 * frc.dram_block_size_bytes
              c.io.out.bits.tiles_per_row.expect(tiles_per_row)
              c.io.out.bits.bram_id_range.expect(bram_id_range)
              c.io.out.bits.bram_id_start.expect(0)
              c.io.out.bits.bram_addr_base.expect(
                current_bram_region * rhs_l0_per_bram * exec_to_fetch_width_ratio
              )

              // Go to next fetch instruction
              c.clock.step(1)
              c.io.out.ready.poke(true)
              c.io.out.valid.expect(true)
              // RHS
              c.io.out.bits.dram_block_count.expect(dram_block_count)
              c.io.out.bits.dram_block_offset_bytes.expect(dram_block_offset_bytes)
              c.io.out.bits.dram_block_size_bytes.expect(dram_block_size_bytes)
              c.io.out.bits.dram_base.expect(
                dram_base_rhs + rhs_l2 * z_l2_per_matrix * rhs_bytes_per_l2 + z_l2 * dram_block_size_bytes
              )

              c.io.out.bits.tiles_per_row.expect(tiles_per_row)
              c.io.out.bits.bram_id_range.expect(bram_id_range)
              c.io.out.bits.bram_id_start.expect(numLHSMems)
              c.io.out.bits.bram_addr_base.expect(
                current_bram_region * rhs_l0_per_bram * exec_to_fetch_width_ratio
              )
              // Go to next fetch instruction

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
      // Configs
      c.io.in.bits.dram_base_lhs.poke(dram_base_lhs)
      c.io.in.bits.dram_base_rhs.poke(dram_base_rhs)
      c.io.in.bits.dram_block_count.poke(dram_block_count)
      c.io.in.bits.dram_block_offset_bytes.poke(dram_block_offset_bytes)
      c.io.in.bits.dram_block_size_bytes.poke(dram_block_size_bytes)
      c.io.in.bits.tiles_per_row.poke(tiles_per_row)

      c.io.in.bits.z_l2_per_matrix.poke(z_l2_per_matrix)
      c.io.in.bits.lhs_l2_per_matrix.poke(lhs_l2_per_matrix)
      c.io.in.bits.rhs_l2_per_matrix.poke(rhs_l2_per_matrix)

      c.io.in.bits.lhs_bytes_per_l2.poke(lhs_bytes_per_l2)
      c.io.in.bits.rhs_bytes_per_l2.poke(rhs_bytes_per_l2)

      c.io.in.valid.poke(true)

      check_golden()
    }
  }
}
