package bismo

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import chisel3.experimental.BundleLiterals._
import BISMOTestHelper._

// TODO fix input params to a more general state
class TestMultiSeqGen extends AnyFreeSpec with ChiselScalatestTester {
  "MultiSeqGen test" in {
    test(new MultiSeqGen(new MultiSeqGenParams(64, 10))) { c =>
      val r = scala.util.Random
      c.io.in.bits.init.poke(0)
      c.io.in.bits.count.poke(10)
      c.io.in.bits.step.poke(1)
      c.io.in.valid.poke(1)
      c.clock.step(1)
      c.io.in.valid.poke(0)
      var ni: Int = 0
      for (i <- 0 until 30) {
        c.io.out.ready.poke(r.nextInt(2))
        if (
          (c.io.out.valid.peek() == true.B && c.io.out.ready.peek() == true.B)
        ) {
          c.io.out.bits.expect(ni)
          ni += 1
        }
        c.clock.step(1)
      }
    }
  }
}
