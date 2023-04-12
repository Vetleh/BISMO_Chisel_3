package bismo

import chisel3._
import chisel3.util._

class ExecOpGeneratorIn() extends Bundle {
  val z_l2_per_matrix = UInt(64.W)
  val lhs_l2_per_matrix = UInt(64.W)
  val rhs_l2_per_matrix = UInt(64.W)
  val lhs_l1_per_l2 = UInt(64.W)
  val rhs_l1_per_l2 = UInt(64.W)
}

class ExecOpGeneratorIO() extends Bundle {
  val in = Flipped(Decoupled(new ExecOpGeneratorIn()))
  val out = Decoupled(new ControllerCmd(2, 2))
}

class ExecOpGenerator() extends Module {
  val counter = RegInit(UInt(32.W), 0.U)

  val rhs_l1 = RegInit(UInt(32.W), 0.U)
  val lhs_l1 = RegInit(UInt(32.W), 0.U)
  val z_l2 = RegInit(UInt(32.W), 0.U)

  val counter2 = RegInit(UInt(2.W), 0.U)

  val io = IO(new ExecOpGeneratorIO())
  // TODO should this be something else?
  io.out.bits.token_channel := 0.U
  io.out.valid := false.B
  io.out.bits.opcode := 0.U
  val total_iters =
    io.in.bits.z_l2_per_matrix *
      io.in.bits.lhs_l2_per_matrix *
      io.in.bits.rhs_l2_per_matrix *
      io.in.bits.lhs_l1_per_l2 *
      io.in.bits.rhs_l1_per_l2

  io.out.valid := false.B
  io.in.ready := true.B

  // TODO enum with the tokens
  when(counter < total_iters && io.in.valid && io.out.ready) {
    io.in.ready := false.B
    io.out.valid := true.B
    // Always happens on this cond
    when(lhs_l1 === 0.U && rhs_l1 === 0.U) {
      // get fetch buffer
      io.out.bits.opcode := 2.U
      io.out.bits.token_channel := 0.U
    }
    // Always happens on this cond
    when(z_l2 === io.in.bits.z_l2_per_matrix - 1.U) {
      // makeinstr_exec_sync_getresultbuffer();
    }

    // Always run
    // makeinstr_exec_run(erc);

    when(z_l2 === io.in.bits.z_l2_per_matrix - 1.U) {
      // makeinstr_exec_sync_putresultbuffer();
    }

    counter := counter + 1.U
    rhs_l1 := 1.U
    when(rhs_l1 === io.in.bits.rhs_l1_per_l2 - 1.U) {
      rhs_l1 := 0.U
      lhs_l1 := lhs_l1 + 1.U
      when(lhs_l1 === io.in.bits.lhs_l1_per_l2 - 1.U) {
        lhs_l1 := 0.U
        z_l2 := z_l2 + 1.U
        when(z_l2 === io.in.bits.z_l2_per_matrix - 1.U) {
          z_l2 := 0.U
        }
      }
    }

  }
}
