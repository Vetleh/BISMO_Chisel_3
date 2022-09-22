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


import Chisel._

// wraps the FPGA-optimized VHDL compressor generator originally developed by
// Thomas Preusser. although the generator supports multi-bit operands, we only
// use it for regular binary operands here.

class BlackBoxCompressorParams(
  val N: Int, // bitwidth of compressor inputs
  val D: Int, // number of pipeline registers, subject to
  val WD: Int = 1, // input operand 1 precision
  val WC: Int = 1 // input operand 2 precision
// compressor tree depth. set to -1 for maximum.
) {
  def headersAsList(): List[String] = {
    return List("Dk", "WA", "WB", "BBCompressorLatency")
  }

  def contentAsList(): List[String] = {
    return List(N, WD, WC, getLatency()).map(_.toString)
  }

  // current compressor tree depth generated for a few values of N.
  val depthMap = Map(32 -> 2, 64 -> 3, 128 -> 3, 256 -> 4, 512 -> 5)

  def getLatency(): Int = {
    if (D == -1) {
      if (depthMap.contains(N)) {
        return depthMap(N)
      } else {
        println(s"WARNING BlackBoxCompressor: Depth for N=$N not precomputed, defaulting to 0")
        return 0
      }
    } else {
      return D
    }
  }
}

// // Chisel Module wrapper around generated compressor
// class BlackBoxCompressor(p: BlackBoxCompressorParams) extends Module {
//   def outputbits = log2Up(p.N) + 1
//   val io = IO(new Bundle {
//     val c = Input(Bits(width = p.N.W))
//     val d = Input(Bits(width = p.N.W))
//     val r = Output(Bits(width = outputbits.W))
//     // val c = Bits(INPUT, width = p.N)
//     // val d = Bits(INPUT, width = p.N)
//     // val r = Bits(OUTPUT, width = outputbits)
//   })
//   val inst = Module(new mac(
//     BB_WA = outputbits, BB_N = p.N, BB_WD = p.WD, BB_WC = p.WC,
//     BB_D = p.getLatency())).io
//   inst.a := 0.U
//   inst <> io
// }

// synthesizable model of BlackBoxCompressor
class BlackBoxCompressorModel(p: BlackBoxCompressorParams) extends Module {
  def outputbits = log2Up(p.N) + 1
  val io = IO(new Bundle {
    val c = Input(Bits(width = p.N.W))
    val d = Input(Bits(width = p.N.W))
    val r = Output(Bits(width = p.N.W))
    // val c = Bits(INPUT, width = p.N)
    // val d = Bits(INPUT, width = p.N)
    // val r = Bits(OUTPUT, width = outputbits)
  })
  io.r := ShiftRegister(PopCount(io.c & io.d), p.getLatency())
}

// actual BlackBox that instantiates the VHDL unit
// class mac(
//   BB_WA: Int, // result precision
//   BB_N: Int, // number of elements in dot product
//   BB_WD: Int, // input operand 1 precision
//   BB_WC: Int, // input operand 2 precision
//   BB_D: Int // optional pipeline regs to add
// ) extends BlackBox(Map("WA" -> BB_WA, // Verilog parameters
//                         "N" -> BB_N,
//                         "WD" -> BB_WD,
//                         "WC" -> BB_WC,
//                         "DEPTH" -> BB_D
//                      )) {
//   val io = new Bundle {
//     // accumulator input is unused
//     val a = Input(Bits(width = BB_WA.W))
//     // val a = Bits(INPUT, width = BB_WA)
//     // c and d are the inputs to the binary dot product
//     val c = Input(Bits(width = (BB_N * BB_WC).W))
//     val d = Input(Bits(width = (BB_N * BB_WC).W))
//     // val c = Bits(INPUT, width = BB_N * BB_WC)
//     // val d = Bits(INPUT, width = BB_N * BB_WD)
//     // r contains the result after D cycles
//     val r = Output(Bits(width = BB_WA.W))
//     // val r = Bits(OUTPUT, width = BB_WA)
//   }
//   // setVerilogParameters(new VerilogParameters {
//   //   val WA: Int = BB_WA
//   //   val N: Int = BB_N
//   //   val WD: Int = BB_WD
//   //   val WC: Int = BB_WC
//   //   val DEPTH: Int = BB_D
//   // })

//   // clock needs to be added manually to BlackBox
//   // addClock(Driver.implicitClock)

//   // Behavioral model for compressor: delayed AND-popcount
//   if (BB_WD == 1 && BB_WC == 1) {
//     io.r := ShiftRegister(PopCount(io.c & io.d), BB_D)
//   }
// }

// Chisel Module wrapper around generated compressor
// class CharacterizationBBCompressor(p: BlackBoxCompressorParams) extends Module {
//   //def outputbits = log2Up(p.N) + 1
//   val io = IO(new Bundle {
//     val c = Input(Bits(width = p.N.W))
//     val d = Input(Bits(width = p.N.W))
//     val r = Input(Bits(width = 32.W))
//     val clean = Input(Bool())
//   })
//   val inst = Module(new BlackBoxCompressor(p)).io

//   val cReg = RegNext(io.c)
//   inst.c := cReg
//   val dReg = RegNext(io.d)
//   inst.d := dReg

//   val rReg = RegInit(0.U(32.W))
//   when(io.clean) {
//     rReg := 0.U
//   }.otherwise {
//     rReg := rReg + inst.r
//   }
//   io.r := rReg
// }
