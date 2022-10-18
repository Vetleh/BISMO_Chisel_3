package bismo

import chisel3._
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import org.scalatest.freespec.AnyFreeSpec
import chisel3.experimental.BundleLiterals._
import BISMOTestHelper._
import fpgatidbits.PlatformWrapper._

class TestBlockStridedRqGen extends AnyFreeSpec with ChiselScalatestTester {
  // Test Params
  val mrp = PYNQZ1Params.toMemReqParams()
  val writeEn = true

  "BlockStridedRqGen test" in {
    test(new BlockStridedRqGen(mrp, writeEn))
      .withAnnotations(Seq(WriteVcdAnnotation)) { c =>
        val r = scala.util.Random
        val base_offs: Int = 0x1000
        val nblocks: Int = 2
        val block_offs: Int = 0x100
        val intra_step: Int = 1
        val intra_burst: Int = 8
        val block_size: Int = 20
        c.io.block_intra_step.poke(intra_step)
        c.io.block_intra_count.poke(block_size)

        c.io.in.bits.base.poke(base_offs)
        c.io.in.bits.block_step.poke(block_offs)
        c.io.in.bits.block_count.poke(nblocks)

        c.io.in.valid.poke(1)
        c.clock.step(1)
        c.io.in.valid.poke(0)

        c.io.out.ready.poke(1)
        for (b <- 0 until nblocks) {
          for (i <- 0 until block_size) {
            while (!c.io.out.valid.peekBoolean()) { c.clock.step(1) }
            c.io.out.bits.addr.expect(
              base_offs + block_offs * b + i * intra_step
            )
            c.io.out.bits.numBytes.expect(intra_step)
            c.io.out.bits.isWrite.expect(1)
            c.clock.step(1)
          }
        }
      }
  }
}
