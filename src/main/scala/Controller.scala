package bismo

import chisel3._
import chisel3.util._
import fpgatidbits.ocm._
import fpgatidbits.streams._
import fpgatidbits.profiler._

// The Controller Module receives instructions, sets up parameters for its
// stage, launches and monitors stage execution, and performs token enq/deq
// operations to ensure the stages execute in correct order.
// Each stage has its own derived *Controller class, defined at the bottom of
// this file.

// Currently, there are two queues associated with each controller:
// - an "op" queue that only contains an opcode (run, send token, receive token)
//   and the token queue ID to send/receive from if applicable
// - a separate "runcfg" queue that contains stage-specific configuration. for
//   each run opcode, an item will be popped from this queue.
object Opcodes {
  val opRun :: opSendToken :: opReceiveToken :: Nil = Enum(3)
}

class ControllerCmd(inChannels: Int, outChannels: Int) extends Bundle {
  val opcode = UInt(2.W)
  val token_channel = UInt(log2Up(math.max(inChannels, outChannels)).W)

}

// base class for all stage controllers, taking in a stream of commands and
// individually executing each while respecting shared resource access locks.
class BaseController[Ts <: Bundle](
    inChannels: Int, // number of input sync channels
    outChannels: Int, // number of output sync channels
    genStageO: => Ts // gentype for stage output
) extends Module {
  val io = IO(new Bundle {
    // command queue input
    val op = Flipped(Decoupled(new ControllerCmd(inChannels, outChannels)))
    // config for run commands
    val runcfg = Flipped(Decoupled(genStageO))
    // enable/disable execution of new instructions for this stage
    val enable = Input(Bool())
    // stage start/stop signals to stage
    val start = Output(Bool())
    val done = Input(Bool())
    // output to stage (config for current run)
    val stageO = Output(genStageO)
    // synchronization channels
    val sync_in = VecInit.fill(inChannels) { Flipped(Decoupled(Bool())) }
    val sync_out = VecInit.fill(outChannels) { Decoupled(Bool()) }
    // state profiler output
    val perf = new Bundle {
      val start = Input(Bool())
      val count = Output(UInt(32.W))
      val sel = Input(UInt(log2Up(4).W))
    }
  })
  // default values
  io.op.ready := false.B
  io.runcfg.ready := false.B
  io.start := false.B
  io.stageO := io.runcfg.bits
  for (i <- 0 until inChannels) { io.sync_in(i).ready := false.B }
  for (i <- 0 until outChannels) {
    io.sync_out(i).valid := false.B
    io.sync_out(i).bits := false.B
  }

  val sGetCmd :: sRun :: sSend :: sReceive :: Nil = Enum(4)
  val regState = RegInit(sGetCmd)

  // NOTE: the following finite state machine assumes that valid will not go
  // once it has gone high. to ensure this, add a FIFO queue to feed the op
  // and runcfg inputs of the controller.
  // TODO maybe get rid of wait states for higher performance
  switch(regState) {
    is(sGetCmd) {
      when(io.op.valid & io.enable) {
        // "peek" into the new command:
        when(
          io.op.bits.opcode === Opcodes.opRun && io.runcfg.valid && !io.done
        ) {
          regState := sRun
        }.elsewhen(io.op.bits.opcode === Opcodes.opSendToken) {
          regState := sSend
        }.elsewhen(io.op.bits.opcode === Opcodes.opReceiveToken) {
          regState := sReceive
        }
      }
    }
    is(sRun) {
      // run stage
      io.start := true.B
      when(io.done) {
        // pop from command queue when done
        io.op.ready := true.B
        io.runcfg.ready := true.B
        // get new command
        regState := sGetCmd
      }
    }
    is(sSend) {
      // send sync token
      val sendChannel = io.sync_out(io.op.bits.token_channel)
      sendChannel.valid := true.B
      when(sendChannel.ready) {
        regState := sGetCmd
        io.op.ready := true.B
      }
    }
    is(sReceive) {
      // receive sync token
      val receiveChannel = io.sync_in(io.op.bits.token_channel)
      receiveChannel.ready := true.B
      when(receiveChannel.valid) {
        regState := sGetCmd
        io.op.ready := true.B
      }
    }
  }

  // state profiler
  val profiler = Module(new StateProfiler(4)).io
  profiler <> io.perf
  profiler.start := io.perf.start & io.enable
  profiler.probe := regState
}

// derived classes for each type of controller.
class FetchController(val myP: FetchStageParams)
    extends BaseController(
      genStageO = new FetchStageCtrlIO(myP),
      inChannels = 1,
      outChannels = 1
    ) {}

class ExecController(val myP: ExecStageParams)
    extends BaseController(
      genStageO = new ExecStageCtrlIO(myP),
      inChannels = 2,
      outChannels = 2
    ) {}

class ResultController(val myP: ResultStageParams)
    extends BaseController(
      genStageO = new ResultStageCtrlIO(myP),
      inChannels = 1,
      outChannels = 1
    ) {}
