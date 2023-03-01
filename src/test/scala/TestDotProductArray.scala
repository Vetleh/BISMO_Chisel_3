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

package bismo

import chisel3._
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import org.scalatest.freespec.AnyFreeSpec
import chisel3.experimental.BundleLiterals._
import BISMOTestHelper._

class TestDotProductArray extends AnyFreeSpec with ChiselScalatestTester {
  // PopCountUnit init
  val num_input_bits = 10
  val pop_count_unit_params = new PopCountUnitParams(num_input_bits)

  // DotProduct init
  val acc_width = 10
  val max_shift_steps = 3
  val dot_product_unit_params =
    new DotProductUnitParams(pop_count_unit_params, acc_width, max_shift_steps)

  // DoProductArray params init
  val m = 10
  val n = 10
  val dot_product_array_params =
    new DotProductArrayParams(dot_product_unit_params, m, n)

  "DotProductArray test" in {
    test(
      new DotProductArray(
        dot_product_array_params
      )
    ).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      val r = scala.util.Random
      // number of re-runs for each test
      val num_seqs = 100
      // number of bits in each operand
      val pc_len = c.p.dpuParams.pcParams.numInputBits
      // max shift steps for random input
      val max_shift = c.p.dpuParams.maxShiftSteps
      // spatial dimensions of the array
      val m = c.p.m
      val n = c.p.n
      // latency from inputs changed to accumulate update
      val latency = c.p.getLatency()

      // helper fuctions for more concise tests
      // wait up to <latency> cycles with valid=0 to create pipeline bubbles
      def randomNoValidWait(max: Int = latency) = {
        val numNoValidCycles = r.nextInt(max + 1)
        c.io.valid.poke(0)
        c.clock.step(numNoValidCycles)
      }

      for (i <- 1 to num_seqs) {
        // generate two random int matrices a[m_test][k_test] and b[n_test][k_test] s.t.
        // m_test % m = 0, n_test % n = 0, k_test % pc_len = 0
        val seq_len = 1 + r.nextInt(17)
        val k_test = pc_len * seq_len
        // TODO add more m and n tiles, clear accumulator in between
        val m_test = m
        val n_test = n
        // precision in bits, each between 1 and max_shift/2 bits
        // such that their sum won't be greater than max_shift
        val precA = 1 + r.nextInt(max_shift / 2)
        val precB = 1 + r.nextInt(max_shift / 2)
        assert(precA + precB <= max_shift)
        // produce random binary test vectors and golden result
        val negA = r.nextBoolean()
        val negB = r.nextBoolean()
        val a = BISMOTestHelper.randomIntMatrix(m_test, k_test, precA, negA)
        val b = BISMOTestHelper.randomIntMatrix(m_test, k_test, precB, negB)
        val golden = BISMOTestHelper.matrixProduct(a, b)
        // iterate over each combination of bit positions for bit serial
        for (bitA <- 0 to precA - 1) {
          val negbitA = negA & (bitA == precA - 1)
          for (bitB <- 0 to precB - 1) {
            // enable negation if combination of bit positions is negative
            val negbitB = negB & (bitB == precB - 1)
            val doNeg = if (negbitA ^ negbitB) 1 else 0
            c.io.negate.poke(doNeg)
            // shift is equal to sum of current bit positions
            c.io.shiftAmount.poke(bitA + bitB)
            for (j <- 0 to seq_len - 1) {
              // set clear bit only on the very first iteration
              val doClear = if (j == 0 & bitA == 0 & bitB == 0) 1 else 0
              c.io.clear_acc.poke(doClear)
              // insert stimulus for left-hand-side matrix tile
              for (i_m <- 0 to m - 1) {
                val seqA_bs =
                  BISMOTestHelper.intVectorToBitSerial(a(i_m), precA)
                val curA = seqA_bs(bitA).slice(j * pc_len, (j + 1) * pc_len)
                c.io.a(i_m).poke(scala.math.BigInt.apply(curA.mkString, 2))
              }
              // insert stimulus for right-hand-side matrix tile
              for (i_n <- 0 to n - 1) {
                val seqB_bs =
                  BISMOTestHelper.intVectorToBitSerial(b(i_n), precB)
                val curB = seqB_bs(bitB).slice(j * pc_len, (j + 1) * pc_len)
                c.io.b(i_n).poke(scala.math.BigInt.apply(curB.mkString, 2))
              }
              c.io.valid.poke(1)
              c.clock.step(1)
              // emulate random pipeline bubbles
              randomNoValidWait()
            }
          }
        }
        // remove valid input in next cycle
        c.io.valid.poke(0)
        // wait until all inputs are processed
        c.clock.step(latency - 1)
        // check produced matrix against golden result
        for (i_m <- 0 to m - 1) {
          for (i_n <- 0 to n - 1) {
            c.io.out(i_m)(i_n).expect(golden(i_m)(i_n))
          }
        }
      }
    }
  }
}
