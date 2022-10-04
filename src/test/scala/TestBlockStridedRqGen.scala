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
import org.scalatest._
import chisel3.experimental.BundleLiterals._
import BISMOTestHelper._
import fpgatidbits.PlatformWrapper._

// TODO fix input params to a more general state
class TestBlockStridedRqGen extends FreeSpec with ChiselScalatestTester {
  "Block strided rq gen test" in {
    test(new BlockStridedRqGen(PYNQZ1Params.toMemReqParams(), true, 0)) { c =>
      val r = scala.util.Random
      val base_offs: Int = 0x1000
      val nblocks: Int = 2
      val block_offs: Int = 0x100
      val intra_step: Int = 1
      val block_size: Int = 4

      c.io.block_intra_step.poke(intra_step.U)
      c.io.block_intra_count.poke(block_size.U)

      c.io.in.bits.base.poke(base_offs.U)
      c.io.in.bits.block_step.poke(block_offs.U)
      c.io.in.bits.block_count.poke(nblocks.U)

      c.io.in.valid.poke(1.B)
      c.clock.step(1)
      c.io.in.valid.poke(0.B)

      c.io.out.ready.poke(1.B)

      for (n <- 0 until 50) {
        c.clock.step(1)
      }

      for (b <- 0 until nblocks) {
        for (i <- 0 until block_size) {

          while (c.io.out.valid.peek() != 1.B) {
            c.clock.step(1)
          }
          c.io.out.bits.addr.expect(
            (base_offs + block_offs * b + i * intra_step).U
          )
          c.io.out.bits.numBytes.expect(intra_step.U)
          c.io.out.bits.isWrite.expect(1.B)
          c.clock.step(1)
        }
      }
    }
  }
}
