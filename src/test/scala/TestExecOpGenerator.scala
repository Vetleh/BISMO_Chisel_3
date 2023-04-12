package bismo

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import chisel3.experimental.BundleLiterals._
import BISMOTestHelper._
import fpgatidbits.dma.MemReqParams

class TestExecOpGenerator() extends AnyFreeSpec with ChiselScalatestTester {

  object ControllOps extends Enumeration {
    type ControllOps = Value
    val opRun, opSendToken, opReceiveToken = Value
  }

  "Test ExecOpGenerator" in {
    test(
      new ExecOpGenerator()
    ).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      val lhs_l2_per_matrix = 0x04
      val rhs_l2_per_matrix = 0x04
      val z_l2_per_matrix = 0x08
      val lhs_l1_per_l2 = 0x04
      val rhs_l1_per_l2 = 0x04

      val token_channel_0 = 0
      val token_channel_1 = 0

      def check_golden() = {
        for (lhs_l2 <- 0 to lhs_l2_per_matrix - 1) {
          for (rhs_l2 <- 0 to rhs_l2_per_matrix - 1) {
            for (z_l2 <- 0 to z_l2_per_matrix - 1) {
              c.io.out.ready.poke(true)
              c.io.out.bits.opcode.expect(ControllOps.opReceiveToken.id)
              c.io.out.bits.token_channel.expect(token_channel_0)
              c.io.out.valid.expect(true)
              c.clock.step(1)
              for (lhs_l1 <- 0 to lhs_l1_per_l2) {
                for (rhs_l1 <- 0 to rhs_l1_per_l2) {
                  if (z_l2 == z_l2_per_matrix - 1) {
                    c.io.out.ready.poke(true)
                    c.io.out.bits.opcode.expect(ControllOps.opReceiveToken.id)
                    c.io.out.bits.token_channel.expect(token_channel_1)
                    c.io.out.valid.expect(true)
                    c.clock.step(1)
                  }

                  c.io.out.ready.poke(true)
                  c.io.out.bits.opcode.expect(ControllOps.opRun.id)
                  c.io.out.bits.token_channel.expect(token_channel_0)
                  c.io.out.valid.expect(true)
                  c.clock.step(1)
                  
                  if (z_l2 == z_l2_per_matrix - 1) {
                    c.io.out.ready.poke(true)
                    c.io.out.bits.opcode.expect(ControllOps.opSendToken.id)
                    c.io.out.bits.token_channel.expect(token_channel_1)
                    c.io.out.valid.expect(true)
                    c.clock.step(1)
                  }

                }
              }
              c.io.out.ready.poke(true)
              c.io.out.bits.opcode.expect(ControllOps.opSendToken.id)
              c.io.out.bits.token_channel.expect(token_channel_0)
              c.io.out.valid.expect(true)
              c.clock.step(1)
            }
          }
        }
      }
      while (c.io.in.valid.peek() === false) {}

      c.io.in.bits.lhs_l2_per_matrix.poke(lhs_l2_per_matrix)
      c.io.in.bits.rhs_l2_per_matrix.poke(rhs_l2_per_matrix)
      c.io.in.bits.z_l2_per_matrix.poke(z_l2_per_matrix)
      c.io.in.valid.poke(true)
      c.clock.step(1)
      check_golden()
    }
  }
}
