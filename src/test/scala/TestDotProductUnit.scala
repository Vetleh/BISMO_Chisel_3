// Copyright (c) 2018 Norwegian University of Science and Technology (NTNU)
// Copyright (c) 2019 Xilinx
//
// BSD v3 License
//
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// * Redistributions of source code must retain the above copyright notice, this
//   list of conditions and the following disclaimer.
//
// * Redistributions in binary form must reproduce the above copyright notice,
//   this list of conditions and the following disclaimer in the documentation
//   and/or other materials provided with the distribution.
//
// * Neither the name of BISMO nor the names of its
//   contributors may be used to endorse or promote products derived from
//   this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import chisel3.experimental.BundleLiterals._
import BISMOTestHelper._

/**
  * This is a trivial example of how to run this Specification
  * From within sbt use:
  * {{{
  * testOnly gcd.GcdDecoupledTester
  * }}}
  * From a terminal shell use:
  * {{{
  * sbt 'testOnly gcd.GcdDecoupledTester'
  * }}}
  */
class TestDotProduct extends AnyFreeSpec with ChiselScalatestTester {
  "DotProductUnit test" in {
    test(new DotProductUnit(new DotProductUnitParams(new PopCountUnitParams(10), 10, 3))) { c =>
      
      val r = scala.util.Random
      // number of re-runs for each test
      val num_seqs = 1
      // number of bits in each operand
      val pc_len = 10
      // max shift steps for random input
      val max_shift = 8
      // latency from inputs changed to accumulate update
      val latency = c.p.getLatency()
      
     
      for(i <- 1 to num_seqs) {
        // clear accumulator between runs
        // clearAcc()
        // // produce random binary test vectors and golden result
        val seqA = BISMOTestHelper.randomIntVector(pc_len, 1, false)
        val seqB = BISMOTestHelper.randomIntVector(pc_len, 1, false)
        val golden = BISMOTestHelper.dotProduct(seqA, seqB)
        
        c.io.in.bits.a.poke(scala.math.BigInt.apply(seqA.mkString, 2))
        c.io.in.bits.b.poke(scala.math.BigInt.apply(seqB.mkString, 2))

        c.io.in.bits.shiftAmount.poke(0)
        c.io.in.bits.negate.poke(0)
        c.io.in.valid.poke(1)
        
        c.clock.step(1)
        
        // remove valid input in next cycle
        c.io.in.valid.poke(0)
        c.clock.step(latency-1)
        println(c.io.out.peek())
        c.io.out.expect(golden)
      }

    }
  }
}
