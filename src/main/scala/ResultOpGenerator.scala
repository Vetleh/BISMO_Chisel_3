package bismo

import chisel3._
import chisel3.util._

class ResultOpGeneratorIn() extends Bundle {
  val lhs_l2_per_matrix = UInt(64.W)
  val rhs_l2_per_matrix = UInt(64.W)
  val lhs_l1_per_l2 = UInt(64.W)
  val rhs_l1_per_l2 = UInt(64.W)
}

class ResultOpGeneratorIO() extends Bundle {
  val in = Flipped(Decoupled(new ResultOpGeneratorIn()))
  val out = Decoupled(new ControllerCmd(1, 1))
}

class ResultOpGenerator() extends Module {
  val counter = RegInit(UInt(32.W), 0.U)
  val counter2 = RegInit(UInt(2.W), 0.U)

  val isHigh = RegInit(Bool(), false.B)
  val init = RegInit(Bool(), true.B)
  val initCounter = RegInit(UInt(1.W), 0.U)

  val io = IO(new ResultOpGeneratorIO())
  // TODO should this be something else?
  io.out.valid := false.B
  io.out.bits.opcode := 0.U
  io.out.bits.token_channel := 0.U

  val total_iters =
    io.in.bits.lhs_l2_per_matrix * io.in.bits.rhs_l2_per_matrix * io.in.bits.lhs_l1_per_l2 * io.in.bits.rhs_l1_per_l2

  io.in.ready := true.B
  when(isHigh && io.in.valid && io.out.ready) {
    isHigh := false.B
    io.in.ready := false.B
    io.out.valid := false.B
  }.otherwise {
    when(init && io.in.valid && io.out.ready) {
      io.in.ready := false.B
      isHigh := true.B
      io.out.valid := true.B

      io.out.bits.opcode := 1.U
      initCounter := initCounter + 1.U
      when(initCounter === 1.U) {
        init := false.B
      }
    }.elsewhen(counter < total_iters && io.in.valid && io.out.ready) {
      isHigh := true.B
      io.in.ready := false.B
      io.out.valid := true.B

      when(counter2 === 0.U) {
        io.out.bits.opcode := 2.U
      }.elsewhen(counter2 === 1.U)(
        io.out.bits.opcode := 0.U
      ).elsewhen(counter2 === 2.U) {
        io.out.bits.opcode := 1.U
        counter := counter + 1.U
      }
      when(counter2 === 2.U) {
        counter2 := 0.U
      }.otherwise {
        counter2 := counter2 + 1.U
      }
    }.elsewhen(counter === total_iters && io.in.valid && io.out.ready) {
      isHigh := true.B
      counter := counter + 1.U
      // Create a wait opcode
      io.out.valid := true.B
      io.out.bits.opcode := 0.U
    }
  }

  when(!io.in.valid) {
    counter := 0.U
    counter2 := 0.U
    isHigh := false.B
    init := true.B
    initCounter := 0.U
  }

}
