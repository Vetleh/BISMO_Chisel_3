package bismo

import chisel3._
import chisel3.util._

class FetchOpGeneratorIn() extends Bundle {
  val z_l2_per_matrix = UInt(32.W)
  val lhs_l2_per_matrix = UInt(32.W)
  val rhs_l2_per_matrix = UInt(32.W)
}

class FetchOpGeneratorIO() extends Bundle {
  val in = Flipped(Decoupled(new FetchOpGeneratorIn()))
  val out = Decoupled(new ControllerCmd(1, 1))
}

class FetchOpGenerator() extends Module {
  val counter = RegInit(UInt(32.W), 0.U)
  val opcode_counter = RegInit(UInt(2.W), 0.U)
  val isHigh = RegInit(Bool(), false.B)

  val io = IO(new FetchOpGeneratorIO())

  io.out.bits.token_channel := 0.U
  io.out.valid := false.B
  io.out.bits.opcode := 0.U
  io.in.ready := true.B

  val total_iters =
    io.in.bits.z_l2_per_matrix * io.in.bits.lhs_l2_per_matrix * io.in.bits.rhs_l2_per_matrix

  when(isHigh && io.in.valid && io.out.ready) {
    isHigh := false.B
    io.in.ready := false.B
    io.out.valid := false.B
  }.elsewhen(counter < total_iters && io.in.valid && io.out.ready) {
    isHigh := true.B
    io.out.valid := true.B
    io.in.ready := false.B
    when(opcode_counter === 0.U) {
      io.out.bits.opcode := 2.U
    }.elsewhen(opcode_counter === 1.U)(
      io.out.bits.opcode := 0.U
    ).elsewhen(opcode_counter === 2.U) {
      io.out.bits.opcode := 0.U
    }.elsewhen(opcode_counter === 3.U) {
      io.out.bits.opcode := 1.U
      counter := counter + 1.U
    }
    opcode_counter := opcode_counter + 1.U
  }
}
