package bismo

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import chisel3.experimental.BundleLiterals._
import BISMOTestHelper._

class TestMultiSeqGen extends AnyFreeSpec with ChiselScalatestTester {
  // MultiSeqGenParams init
  val w = 64
  val a = 10
  val multi_seg_gen_params = new MultiSeqGenParams(w, a)

  "MultiSeqGen test" in {
    test(new MultiSeqGen(multi_seg_gen_params)) { c =>
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
        if ((c.io.out.valid.peekBoolean() && c.io.out.ready.peekBoolean())) {
          c.io.out.bits.expect(ni)
          ni += 1
        }
        c.clock.step(1)
      }
    }
  }
}
