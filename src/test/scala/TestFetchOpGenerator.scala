package bismo

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import chisel3.experimental.BundleLiterals._
import BISMOTestHelper._
import fpgatidbits.dma.MemReqParams

class TestFetchOpGenerator() extends AnyFreeSpec with ChiselScalatestTester {

  object ControllOps extends Enumeration {
    type ControllOps = Value
    val opRun, opSendToken, opReceiveToken = Value
  }

  "Test FetchOpGenerator" in {
    test(
      new FetchOpGenerator()
    ).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      val lhs_l2_per_matrix = 0x04
      val rhs_l2_per_matrix = 0x04
      val z_l2_per_matrix = 0x08

      val token_channel = 0
      def check_golden() = {
        var total_iters =
          lhs_l2_per_matrix * rhs_l2_per_matrix * z_l2_per_matrix
        for (iter <- 0 to total_iters - 1) {

          c.io.out.ready.poke(true)
          c.io.out.bits.opcode.expect(ControllOps.opReceiveToken.id)
          c.io.out.bits.token_channel.expect(token_channel)
          c.io.out.valid.expect(true)
          c.clock.step(1)

          c.io.out.ready.poke(true)
          c.io.out.bits.opcode.expect(ControllOps.opRun.id)
          c.io.out.bits.token_channel.expect(token_channel)
          c.io.out.valid.expect(true)
          c.clock.step(1)

          c.io.out.ready.poke(true)
          c.io.out.bits.opcode.expect(ControllOps.opRun.id)
          c.io.out.bits.token_channel.expect(token_channel)
          c.io.out.valid.expect(true)
          c.clock.step(1)

          c.io.out.ready.poke(true)
          c.io.out.bits.opcode.expect(ControllOps.opSendToken.id)
          c.io.out.bits.token_channel.expect(token_channel)
          c.io.out.valid.expect(true)
          c.clock.step(1)
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
