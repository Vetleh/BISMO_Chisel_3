package bismo

import chisel3._
import chisel3.util._

class FetchOpGeneratorIn() extends Bundle {
  val z_l2_per_matrix = UInt(64.W)
  val lhs_l2_per_matrix = UInt(64.W)
  val rhs_l2_per_matrix = UInt(64.W)
}

class FetchOpGeneratorIO() extends Bundle {
  val in = Flipped(Decoupled(new FetchOpGeneratorIn()))
  val out = Decoupled(new ControllerCmd(1, 1))
}

class FetchOpGenerator() extends Module {
  val counter = RegInit(UInt(32.W), 0.U)
  val counter2 = RegInit(UInt(2.W), 0.U)
  val isHigh = RegInit(Bool(), false.B)

  val io = IO(new FetchOpGeneratorIO())
  // TODO should this be something else?
  io.out.bits.token_channel := 0.U
  io.out.valid := false.B
  io.out.bits.opcode := 0.U
  val total_iters =
    io.in.bits.z_l2_per_matrix * io.in.bits.lhs_l2_per_matrix * io.in.bits.rhs_l2_per_matrix
  io.in.ready := true.B

  when(isHigh && io.in.valid && io.out.ready) {
    isHigh := false.B
    io.in.ready := false.B
    io.out.valid := false.B
  }.otherwise {
    when(counter < total_iters && io.in.valid && io.out.ready) {
      isHigh := true.B
      io.out.valid := true.B
      when(counter2 === 0.U) {
        io.out.bits.opcode := 2.U
      }.elsewhen(counter2 === 1.U)(
        io.out.bits.opcode := 0.U
      ).elsewhen(counter2 === 2.U) {
        io.out.bits.opcode := 0.U
      }.elsewhen(counter2 === 3.U) {
        io.out.bits.opcode := 1.U
        counter := counter + 1.U
      }
      counter2 := counter2 + 1.U
    }
  }
}
