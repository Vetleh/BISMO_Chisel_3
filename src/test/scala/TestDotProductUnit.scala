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
class TestDotProductUnit extends AnyFreeSpec with ChiselScalatestTester {
  "DotProductUnit test" in {
    test(new DotProductUnit(new DotProductUnitParams(new PopCountUnitParams(10), 32, 16))) { c =>
      
      val r = scala.util.Random
      // number of re-runs for each test
      val num_seqs = 100
      // number of bits in each operand
      val pc_len = 10
      // max shift steps for random input
      val max_shift = 8
      // latency from inputs changed to accumulate update
      val latency = c.p.getLatency()
      
      // helper fuctions for more concise tests
      // wait up to <latency> cycles with valid=0 to create pipeline bubbles
      def randomNoValidWait(max: Int = latency) = {
        val numNoValidCycles = r.nextInt(max+1)
        c.io.in.valid.poke(0)
        c.clock.step(numNoValidCycles)
      }

      def clearAcc(waitUntilCleared: Boolean = false) = {
        // note that clearing happens right before the regular binary dot
        // product result is added -- so also set inputs explicitly to zero.
        // normally the testbench would just set the clear_acc of the very
        // first test vector to 1 alongside the regular data, and to 0 after,
        // but this also works.
        c.io.in.bits.a.poke(0)
        c.io.in.bits.b.poke(0)
        c.io.in.bits.clear_acc.poke(1)
        c.io.in.valid.poke(1)
        c.clock.step(1)
        c.io.in.bits.clear_acc.poke(0)
        c.io.in.valid.poke(0)
        if(waitUntilCleared) {
          c.clock.step(latency)
        }
      }

      // clear accumulators, wait until clear is visible
      clearAcc(waitUntilCleared = true)
      c.io.out.expect(0)
      c.clock.step(10)
      // accumulator should retain value without any valid input
      c.io.out.expect(0)      
     
      
      for(i <- 1 to num_seqs) {
        // clear accumulator between runs
        clearAcc()
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
        
        c.io.in.valid.poke(0)
        c.clock.step(latency-1)
        c.io.out.expect(golden)
      }

      // test 2: binary, unsigned vectors that do not fit into popCountWidth
      for(i <- 1 to num_seqs) {
        // produce seq_len different popcount vectors, each pc_len bits
        // min length of seq_len is 1, no max len (barring accumulator overflow)
        // but set to 16 here for quicker testing
        val seq_len = 1 + r.nextInt(17)
        val bit_len = pc_len * seq_len
        // produce random binary test vectors and golden result
        val seqA = BISMOTestHelper.randomIntVector(bit_len, 1, false)
        val seqB = BISMOTestHelper.randomIntVector(bit_len, 1, false)
        val golden = BISMOTestHelper.dotProduct(seqA, seqB)
        // clear accumulator between runs
        clearAcc()
        for(j <- 0 to seq_len-1) {
          // push in next slice of bit vector
          val curA = seqA.slice(j*pc_len, (j+1)*pc_len)
          val curB = seqB.slice(j*pc_len, (j+1)*pc_len)
          c.io.in.bits.a.poke(scala.math.BigInt.apply(curA.mkString, 2))
          c.io.in.bits.b.poke(scala.math.BigInt.apply(curB.mkString, 2))
          c.io.in.bits.shiftAmount.poke(0)
          c.io.in.bits.negate.poke(0)
          c.io.in.valid.poke(1)
          c.clock.step(1)
          // emulate random pipeline bubbles
          randomNoValidWait()
        }
        // remove valid input in next cycle
        c.io.in.valid.poke(0)
        // wait until all inputs are processed
        c.clock.step(latency-1)
        c.io.out.expect(golden)
      }

      // test 3: multibit unsigned and signed integers
      for(i <- 1 to num_seqs) {
        // produce seq_len different popcount vectors, each pc_len bits
        // min length of seq_len is 1, no max len (barring accumulator overflow)
        // but set to 16 here for quicker testing
        val seq_len = 1 + r.nextInt(17)
        val bit_len = pc_len * seq_len
        // precision in bits, each between 1 and max_shift/2 bits
        // such that their sum won't be greater than max_shift
        val precA = 1 + r.nextInt(max_shift/2)
        val precB = 1 + r.nextInt(max_shift/2)
        assert(precA + precB <= max_shift)
        // produce random binary test vectors and golden result
        val negA = r.nextBoolean()
        val negB = r.nextBoolean()
        val seqA = BISMOTestHelper.randomIntVector(bit_len, precA, negA)
        val seqB = BISMOTestHelper.randomIntVector(bit_len, precB, negB)
        val golden = BISMOTestHelper.dotProduct(seqA, seqB)
        // convert test vectors to bit serial form
        val seqA_bs = BISMOTestHelper.intVectorToBitSerial(seqA, precA)
        val seqB_bs = BISMOTestHelper.intVectorToBitSerial(seqB, precB)
        // clear accumulator between runs
        clearAcc()
        var golden_acc: Int = 0
        // iterate over each combination of bit positions for bit serial
        for(bitA <- 0 to precA-1) {
          val negbitA = negA & (bitA == precA-1)
          for(bitB <- 0 to precB-1) {
            val negbitB = negB & (bitB == precB-1)
            val doNeg = if(negbitA ^ negbitB) 1 else 0
            // shift is equal to sum of current bit positions
            c.io.in.bits.shiftAmount.poke(bitA+bitB)
            for(j <- 0 to seq_len-1) {
              // push in next slice of bit vector from correct bit position
              val curA = seqA_bs(bitA).slice(j*pc_len, (j+1)*pc_len)
              val curB = seqB_bs(bitB).slice(j*pc_len, (j+1)*pc_len)
              golden_acc += BISMOTestHelper.dotProduct(curA, curB) << (bitA+bitB)
              c.io.in.bits.a.poke(scala.math.BigInt.apply(curA.mkString, 2))
              c.io.in.bits.b.poke(scala.math.BigInt.apply(curB.mkString, 2))
              c.io.in.bits.negate.poke(doNeg)
              c.io.in.valid.poke(1)
              c.clock.step(1)
              // emulate random pipeline bubbles
              randomNoValidWait()
            }
          }
        }
        // remove valid input in next cycle
        c.io.in.valid.poke(0)
        // wait until all inputs are processed
        c.clock.step(latency-1)
        c.io.out.expect(golden)
      }
    }
  }
}
