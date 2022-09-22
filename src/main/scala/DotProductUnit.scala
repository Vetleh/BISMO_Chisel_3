import Chisel._


// The DotProductUnit computes a binary dot product over time,
// with possibilities for shifting (for weighting by powers-of-two)
// and negative contributions (for signed numbers).
// structurally, it is a AND-popcount-shift-accumulate datapath.

class DotProductUnitParams(
  // popcount module input width (bits per cycle)
  val pcParams: PopCountUnitParams,
  // width of accumulator register
  val accWidth: Int,
  // maximum number of shift steps
  val maxShiftSteps: Int,
  // do not instantiate the shift stage
  val noShifter: Boolean = false,
  // do not instantiate the negate stage
  val noNegate: Boolean = false,
  // extra regs for retiming
  val extraPipelineRegs: Int = 0
) {
  // internal pipeline registers inside DPU
  val myLatency = 6 + extraPipelineRegs
  // latency of instantiated PopCountUnit
  val popcountLatency: Int = pcParams.getLatency()
  // return total latency
  def getLatency(): Int = {
    return myLatency + popcountLatency
  }
  def headersAsList(): List[String] = {
    return pcParams.headersAsList() ++ List("AccWidth", "NoShift", "NoNeg", "DPULatency")
  }

  def contentAsList(): List[String] = {
    return pcParams.contentAsList() ++ List(accWidth, noShifter, noNegate, getLatency()).map(_.toString)
  }
}

class DotProductStage0(p: DotProductUnitParams) extends Bundle {
  // bit vectors for dot product
  val a = Bits(p.pcParams.numInputBits.W)
  val b = Bits(p.pcParams.numInputBits.W)
  // number of steps to left shift result by before accumulation
  val shiftAmount = UInt(log2Up(p.maxShiftSteps+1).W)
  // whether to negate result before accumulation
  val negate = Bool()
  // whether to clear the accumulator before adding the new result
  val clear_acc = Bool()

}

// Bundles of partially-processed input through the pipelined datapath
class DotProductStage1(p: DotProductUnitParams) extends Bundle {
  // result of AND of inputs
  val andResult = Bits(p.pcParams.numInputBits.W)
  // number of steps to left shift result by before accumulation
  val shiftAmount = UInt(log2Up(p.maxShiftSteps).W)
  // whether to negate result before accumulation
  val negate = Bool()
  // whether to clear the accumulator before adding the new result
  val clear_acc = Bool()
}

class DotProductStage2(p: DotProductUnitParams) extends Bundle {
  // result of popcount
  val popcountResult = UInt(log2Up(p.pcParams.numInputBits+1).W)
  // number of steps to left shift result by before accumulation
  val shiftAmount = UInt(log2Up(p.maxShiftSteps).W)
  // whether to negate result before accumulation
  val negate = Bool()
  // whether to clear the accumulator before adding the new result
  val clear_acc = Bool()

}

class DotProductStage3(p: DotProductUnitParams) extends Bundle {
  // result of shift
  val shiftResult = UInt(p.accWidth.W)
  // whether to negate result before accumulation
  val negate = Bool()
  // whether to clear the accumulator before adding the new result
  val clear_acc = Bool()

}

class DotProductStage4(p: DotProductUnitParams) extends Bundle {
  // result of negate
  val negateResult = UInt(p.accWidth.W)
  // whether to clear the accumulator before adding the new result
  val clear_acc = Bool()

}

class DotProductUnit(val p: DotProductUnitParams) extends Module {
  val io = new Bundle {
    val in = Input(Valid(new DotProductStage0(p)))
    val out = Output(UInt(p.accWidth.W))
  }
  // instantiate the popcount unit
  val modPopCount = Module(new PopCountUnit(p.pcParams))
  //when(io.in.valid) { printf("DPU operands are %x and %x\n", io.in.bits.a, io.in.bits.b) }
  // core AND-popcount-shift part of datapath
  // note that the valid bit and the actual pipeline contents are
  // treated differently to save FPGA resources: valid pipeline regs
  // are initialized to 0, whereas actual data regs aren't initialized

  // extra pipeline regs at the input
  val regInput = ShiftRegister(io.in, p.extraPipelineRegs)


  // pipeline stage 0: register the input
  val regStage0_v = RegNext(Wire(regInput.valid), false.B)
  val regStage0_b = RegNext(Wire(regInput.bits))
  //when(regStage0_v) { printf("Stage0: a %x b %x shift %d neg %d clear %d\n", regStage0_b.a, regStage0_b.b, regStage0_b.shiftAmount, regStage0_b.negate, regStage0_b.clear_acc)}

  // pipeline stage 1: AND the bit vector inputs
  val stage1 = Wire(new DotProductStage1(p))
  stage1.andResult := regStage0_b.a & regStage0_b.b
  stage1.shiftAmount := regStage0_b.shiftAmount
  stage1.negate := regStage0_b.negate
  stage1.clear_acc := regStage0_b.clear_acc
  val regStage1_v = RegNext(regStage0_v, false.B)
  val regStage1_b = RegNext(stage1)
  //when(regStage1_v) { printf("Stage1: andResult %x shift %d neg %d clear %d\n", regStage1_b.andResult, regStage1_b.shiftAmount, regStage1_b.negate, regStage1_b.clear_acc)}

  // pipeline stage 2: popcount the result of AND
  val stage2 = Wire(new DotProductStage2(p))
  modPopCount.io.in := regStage1_b.andResult
  stage2.popcountResult := modPopCount.io.out
  // need extra delays on pass-through parts due to pipelined popcount
  stage2.shiftAmount := ShiftRegister(regStage1_b.shiftAmount, p.popcountLatency)
  stage2.negate := ShiftRegister(regStage1_b.negate, p.popcountLatency)
  stage2.clear_acc := ShiftRegister(regStage1_b.clear_acc, p.popcountLatency)
  val stage2_pc_v = ShiftRegister(regStage1_v, p.popcountLatency)
  val regStage2_v = RegNext(stage2_pc_v, false.B)
  val regStage2_b = RegNext(stage2)
  //when(regStage2_v) { printf("Stage2: popCResult %d shift %d neg %d clear %d\n", regStage2_b.popcountResult, regStage2_b.shiftAmount, regStage2_b.negate, regStage2_b.clear_acc)}

  // pipeline stage 3: shift
  val stage3 = Wire(new DotProductStage3(p))
  if(p.noShifter) {
    stage3.shiftResult := regStage2_b.popcountResult
  } else {
    stage3.shiftResult := regStage2_b.popcountResult << regStage2_b.shiftAmount
  }
  stage3.negate := regStage2_b.negate
  stage3.clear_acc := regStage2_b.clear_acc
  val regStage3_v = RegNext(regStage2_v, false.B)
  val regStage3_b = RegNext(stage3)
  //when(regStage3_v) { printf("Stage3: shiftRes %d neg %d clear %d\n", regStage3_b.shiftResult, regStage3_b.negate, regStage3_b.clear_acc)}

  // pipeline stage 4: negate
  val stage4 = Wire(new DotProductStage4(p))
  val shiftRes = regStage3_b.shiftResult
  if(p.noNegate) {
    stage4.negateResult := shiftRes
  } else {
    stage4.negateResult := Mux(regStage3_b.negate, -shiftRes, shiftRes)
  }
  stage4.clear_acc := regStage3_b.clear_acc
  val regStage4_v = RegNext(regStage3_v, false.B)
  val regStage4_b = RegNext(stage4)
  // accumulator register for the dot product. cleared with clear_acc
  val regAcc = Reg(UInt(p.accWidth.W))
  //when(regStage4_v) { printf("Stage4: negResult %d clear %d acc: %d\n", regStage4_b.negateResult, regStage4_b.clear_acc, regAcc)}
  // accumulate new input when valid
  when(regStage4_v) {
    when(regStage4_b.clear_acc) {
      regAcc := regStage4_b.negateResult
    } .otherwise {
      regAcc := regAcc + regStage4_b.negateResult
    }
  }

  // expose the accumulator output directly
  io.out := regAcc
}