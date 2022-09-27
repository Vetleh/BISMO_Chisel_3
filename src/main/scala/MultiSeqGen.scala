package bismo

import chisel3._
import chisel3.util._

// TODO: reusable component, move into tidbits

// MultiSeqGen, "multiple sequence generator", is a sequence generator that
// receives a stream of sequence descriptions (init, count, step) as input.
// each description is a command that generates the corresponding arithmetic
// sequence. when the command is finished, the next command's sequence is
// genertaed.

// example:
// two commands (init=0, count=4, step=1) and (init=10, count=3, step=2)
// would generate the following sequence
// 0 1 2 3 10 12 14

class MultiSeqGenParams(
    val w: Int,
    val a: Int
) {
  def headersAsList(): List[String] = {
    return List("datawidth", "countwidth")
  }
  def contentAsList(): List[String] = {
    return List(w, a).map(_.toString)
  }
}

class MultiSeqGenCtrl(p: MultiSeqGenParams) extends Bundle {
  val init = Input(UInt(p.w.W))
  val count = Input(UInt(p.a.W))
  val step = Input(UInt(p.w.W))
}

class MultiSeqGen(p: MultiSeqGenParams) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new MultiSeqGenCtrl(p)))
    val out = Decoupled(UInt(p.w.W))
  })
  val regSeqElem = Reg(UInt(p.w.W))
  val regCounter = Reg(UInt(p.a.W))
  val regMaxCount = Reg(UInt(p.a.W))
  val regStep = Reg(UInt(p.w.W))
  io.in.ready := false.B
  io.out.valid := false.B
  io.out.bits := regSeqElem

  val sIdle :: sRun :: Nil = Enum(2)
  val regState = RegInit(sIdle)

  /*printf("regState = %d\n", regState)
  printf("regSeqElem = %d\n", regSeqElem)
  printf("regCounter = %d\n", regCounter)
  printf("regMaxCount = %d\n", regMaxCount)
  printf("regStep = %d\n", regStep)*/

  switch(regState) {
    is(sIdle) {
      io.in.ready := true.B
      when(io.in.valid) {
        regState := sRun
        regCounter := 0.U
        regSeqElem := io.in.bits.init
        regMaxCount := io.in.bits.count
        regStep := io.in.bits.step
      }
    }

    is(sRun) {
      when(regCounter === regMaxCount) {
        regState := sIdle
      }.otherwise {
        io.out.valid := true.B
        when(io.out.ready) {
          regCounter := regCounter + 1.U
          regSeqElem := regSeqElem + regStep
        }
      }
    }
  }
}
