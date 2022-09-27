package bismo

import chisel3._
import chisel3.util._

// The DotProductArray is a two-dimensional array of DotProductUnits,
// computing/accumulating a bit-serial matrix multiplication every cycle.

class DotProductArrayParams(
    // parameters for each DotProductUnit
    val dpuParams: DotProductUnitParams,
    // dot product array dimensions
    val m: Int, // rows of left-hand-side matrix (LHS) per cycle
    val n: Int, // cols of right-hand-side matrix (RHS) per cycle
    // extra register levels in broadcast interconnect
    val extraPipelineRegs: Int = 0
) {
  // latency of instantiated DPUs
  val dpuLatency: Int = dpuParams.getLatency()
  // contributed latency of DPA due to interconnect pipelining
  val myLatency: Int = 1 + extraPipelineRegs
  def getLatency(): Int = {
    return myLatency + dpuLatency
  }
  def headersAsList(): List[String] = {
    return dpuParams.headersAsList() ++ List("M", "N")
  }

  def contentAsList(): List[String] = {
    return dpuParams.contentAsList() ++ List(m, n).map(_.toString)
  }
}

class DotProductArray(val p: DotProductArrayParams) extends Module {
  val io = IO(new Bundle {
    // inputs broadcasted to each DPU
    val valid = Input(Bool())
    val shiftAmount = Input(UInt(log2Up(p.dpuParams.maxShiftSteps + 1).W))
    val negate = Input(Bool())
    val clear_acc = Input(Bool())
    // DPU bit inputs, connected appropriately to 2D array
    val a = Input(Vec(p.m, Bits(p.dpuParams.pcParams.numInputBits.W)))
    val b = Input(Vec(p.m, Bits(p.dpuParams.pcParams.numInputBits.W)))
    // DPU outputs from each accumulator
    // val out = Output(Vec(p.m, Vec(p.n, UInt(p.dpuParams.accWidth.W))))
    val out = Output(Vec(p.m, Vec(p.n, SInt(p.dpuParams.accWidth.W))))
  })

  // val a =  VecInit.fill(p.m) { io.tempA }
  // val b =  VecInit.fill(p.m) { io.tempB }

  // instantiate the array of DPUs
  val dpu = VecInit.fill(p.m, p.n) {
    Module(new DotProductUnit(p.dpuParams)).io
  }

  // connect the array of DPUs to the inputs
  for (i <- 0 to p.m - 1) {
    for (j <- 0 to p.n - 1) {
      // common broadcast inputs
      dpu(i)(j).in.valid := ShiftRegister(io.valid, p.myLatency)
      dpu(i)(j).in.bits.shiftAmount := ShiftRegister(
        io.shiftAmount,
        p.myLatency
      )
      dpu(i)(j).in.bits.negate := ShiftRegister(io.negate, p.myLatency)
      dpu(i)(j).in.bits.clear_acc := ShiftRegister(io.clear_acc, p.myLatency)
      // dot product bit inputs, connect along rows and columns
      dpu(i)(j).in.bits.a := ShiftRegister(io.a(i), p.myLatency)
      dpu(i)(j).in.bits.b := ShiftRegister(io.b(j), p.myLatency)
      // expose accumulators
      io.out(i)(j) := dpu(i)(j).out
    }
  }

  // NOTE: the current DPA interconnect is not systolic (i.e. no regs between
  // rows/cols), which simplifies control and increases utilization, but may
  // limit scaling to large DPA sizes.
}
