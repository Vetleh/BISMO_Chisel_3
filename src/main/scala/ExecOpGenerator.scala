package bismo

import chisel3._
import chisel3.util._

class ExecOpGeneratorIn() extends Bundle {
  val z_l2_per_matrix = UInt(32.W)
  val lhs_l2_per_matrix = UInt(32.W)
  val rhs_l2_per_matrix = UInt(32.W)
  val lhs_l1_per_l2 = UInt(16.W)
  val rhs_l1_per_l2 = UInt(16.W)
}

class ExecOpGeneratorIO() extends Bundle {
  val in = Flipped(Decoupled(new ExecOpGeneratorIn()))
  val out = Decoupled(new ControllerCmd(2, 2))
}

class ExecOpGenerator() extends Module {
  val counter = RegInit(UInt(32.W), 0.U)

  val inner_loop = RegInit(UInt(32.W), 0.U)
  val z_l2 = RegInit(UInt(32.W), 0.U)

  val counter2 = RegInit(UInt(2.W), 0.U)

  val sync_putfetchbuffer = RegInit(Bool(), false.B)
  val init = RegInit(Bool(), true.B)
  val initCounter = RegInit(UInt(1.W), 0.U)

  val sync_getbuffer = RegInit(Bool(), true.B)
  val isHigh = RegInit(Bool(), false.B)

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

  def generate_op(opcode: UInt, token_channel: UInt) = {
    io.out.bits.opcode := opcode
    io.out.bits.token_channel := token_channel
  }

  // Non valid signal for cycle so the queue can differentiate between different signals
  when(isHigh && io.in.valid && io.out.ready) {
    isHigh := false.B
    io.in.ready := false.B
    io.out.valid := false.B
  }.elsewhen(counter < total_iters && io.in.valid && io.out.ready) {
    isHigh := true.B
    io.in.ready := false.B
    io.out.valid := true.B
    // Generate initial tokens
    when(init && io.in.valid && io.out.ready) {
      io.out.bits.opcode := 1.U
      initCounter := initCounter + 1.U
      when(initCounter === 1.U) {
        init := false.B
      }
    }.otherwise {
      when(sync_putfetchbuffer) {
        // finished processing L2 tile
        // exec releases input matrix buffers
        generate_op(1.U, 0.U)

        counter := counter + 1.U
        inner_loop := inner_loop + 1.U
        when(inner_loop === io.in.bits.lhs_l1_per_l2 * io.in.bits.rhs_l1_per_l2 - 1.U) {
          inner_loop := 0.U
          z_l2 := z_l2 + 1.U
          when(z_l2 === io.in.bits.z_l2_per_matrix - 1.U) {
            z_l2 := 0.U
          }
        }
        sync_putfetchbuffer := false.B
      }.elsewhen(inner_loop === 0.U && sync_getbuffer) {
        generate_op(2.U, 0.U)
        sync_getbuffer := false.B
      }.elsewhen(z_l2 === io.in.bits.z_l2_per_matrix - 1.U) {
        when(counter2 === 0.U) {
          generate_op(2.U, 1.U)

          counter2 := counter2 + 1.U
        }.elsewhen(counter2 === 1.U) {
          generate_op(0.U, 0.U)

          counter2 := counter2 + 1.U
        }.otherwise {
          generate_op(1.U, 1.U)

          sync_getbuffer := true.B
          counter2 := 0.U
          when(inner_loop === io.in.bits.lhs_l1_per_l2 * io.in.bits.rhs_l1_per_l2 - 1.U) {
            sync_putfetchbuffer := true.B
          }.otherwise {
            counter := counter + 1.U
            inner_loop := inner_loop + 1.U
            when(
              inner_loop === io.in.bits.lhs_l1_per_l2 * io.in.bits.rhs_l1_per_l2 - 1.U
            ) {
              inner_loop := 0.U
              z_l2 := z_l2 + 1.U
              when(z_l2 === io.in.bits.z_l2_per_matrix - 1.U) {
                z_l2 := 0.U
              }
            }
          }
        }
      }.otherwise {
        // run
        generate_op(0.U, 0.U)
        sync_getbuffer := true.B
        when(inner_loop === io.in.bits.lhs_l1_per_l2 * io.in.bits.rhs_l1_per_l2 - 1.U) {
          sync_putfetchbuffer := true.B
        }.otherwise {
          when(inner_loop === io.in.bits.lhs_l1_per_l2 * io.in.bits.rhs_l1_per_l2 - 1.U) {
            inner_loop := 0.U
            z_l2 := z_l2 + 1.U
            when(z_l2 === io.in.bits.z_l2_per_matrix - 1.U) {
              z_l2 := 0.U
            }
          }
          counter := counter + 1.U
          inner_loop := inner_loop + 1.U
        }
      }
    }
  }
}
