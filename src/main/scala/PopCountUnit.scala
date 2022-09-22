import Chisel._

// a popcount module with configurable pipelining
// note: retiming must be enabled for pipelining to work as intended
// e.g. the following in Xilinx Vivado:
//set_property STEPS.SYNTH_DESIGN.ARGS.RETIMING true [get_runs synth_1]

class PopCountUnitParams(
  val numInputBits: Int,            // popcount bits per cycle
  val extraPipelineRegs: Int = 0  // extra I/O registers for retiming
) {
  def headersAsList(): List[String] = {
    return List("PopCWidth", "PopCLatency")
  }

  def contentAsList(): List[String] = {
    return List(numInputBits, getLatency()).map(_.toString)
  }

  def getNumCompressors(): Int = {
    return (1 << log2Ceil(math.ceil(numInputBits / 36.0).toInt))
  }

  def getPadBits(): Int = {
    return getNumCompressors()*36 - numInputBits
  }

  def padInput(in: Bits): Bits = {
    if(getPadBits() == 0) { return in }
    else { return Cat(Bits(0, width = getPadBits()), in) }
  }

  // levels of I/O regs inserted by default
  val defaultInputRegs: Int = 1
  val defaultOutputRegs: Int = 1
  val adderTreeRegs: Int = log2Ceil(getNumCompressors())
  def getLatency(): Int = {
    return defaultInputRegs + defaultOutputRegs + adderTreeRegs + extraPipelineRegs
  }
}


// optimized 36-to-6 popcount as described on Jan Gray's blog:
// http://fpga.org/2014/09/05/quick-fpga-hacks-population-count/
class PopCount6to3() extends Module {
  val io = new Bundle {
    val in = Input(Bits(width = 6))
    val out = Output(UInt(width = 3))
  }
  def int_popcount(b: Int, nbits: Int): Int = {
    var ret = 0
    for(i <- 0 until nbits) {
      ret += (b >> i) & 1
    }
    return ret
  }
  val lut_entires = 1 << 6
  val lookup = Vec.tabulate(lut_entires) {
    i: Int => UInt(int_popcount(i, 6), width = 3)
  }
  io.out := lookup(io.in)
}

class PopCount36to6() extends Module {
  val io = new Bundle {
    val in = Input(Bits(width = 36))
    val out = Output(UInt(width = 6))
  }
  val stage1 = Vec.fill(6) { Module(new PopCount6to3()).io }
  for(i <- 0 until 6) {
    stage1(i).in := io.in((i+1)*6 - 1, i*6)
  }
  val stage2 = Vec.fill(3) { Module(new PopCount6to3()).io }
  for(i <- 0 until 3) {
    stage2(i).in := Cat(stage1.map(_.out(i)))
  }

  val contrib2 = Cat(UInt(0, width=1), stage2(2).out << 2)
  val contrib1 = Cat(UInt(0, width=2), stage2(1).out << 1)
  val contrib0 = Cat(UInt(0, width=3), stage2(0).out << 0)

  io.out := contrib0 + contrib1 + contrib2
  //printf("36to6 in: %x out %d \n", io.in, io.out)
}

class PopCountUnit(
  val p: PopCountUnitParams
) extends Module {
  val io = new Bundle {
    // input vector
    val in = Input(Bits(width = p.numInputBits))
    // number of set bits in input vector
    // why the +1 here? let's say we have a 2-bit input 11, with two
    // bits set. log2Up(2) is 1, but 1 bit isn't enough to represent
    // the number 2.
    val out = Output(UInt(width = log2Up(p.numInputBits+1)))
  }
  val pcs = Vec.fill(p.getNumCompressors()) { Module(new PopCount36to6()).io }
  val inWire = p.padInput(io.in)
  val inReg = ShiftRegister(inWire, p.defaultInputRegs + p.extraPipelineRegs)
  val outWire = Wire(UInt(width = log2Up(p.numInputBits+1)))

  for(i <- 0 until p.getNumCompressors()) {
    pcs(i).in := inReg((i+1)*36-1, i*36)
  }

  outWire := RegAdderTree(pcs.map(_.out))

  val outReg = Wire(UInt(width = log2Up(p.numInputBits+1)))
  outReg := ShiftRegister(outWire, p.defaultOutputRegs)
  io.out := outReg
}

object RegAdderTree
{
  def apply(in: Iterable[UInt]): UInt = {
    if (in.size == 0) {
      UInt(0)
    } else if (in.size == 1) {
      in.head
    } else {
      Predef.assert(in.size % 2 == 0)
      Reg(next = apply(in.slice(0, in.size/2)) + Cat(UInt(0), apply(in.slice(in.size/2, in.size))))
    }
  }
}