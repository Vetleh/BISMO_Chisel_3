package bismo

import chisel3._
import chisel3.util._
import fpgatidbits.dma._
import fpgatidbits.streams._
import fpgatidbits.ocm._

// the BlockStridedRqGen generates memory requests that span several blocks.
// each block may or may not be contiguous, and there may be strides between
// blocks as well. this is a form of 2D DMA.

class BlockStridedRqDescriptor(mrp: MemReqParams) extends Bundle {
  val base = UInt(mrp.addrWidth.W)
  val block_step = UInt(mrp.addrWidth.W)
  val block_count = UInt(mrp.addrWidth.W)

}

class BlockStridedRqGen(
    mrp: MemReqParams,
    writeEn: Boolean,
    chanID: Int = 0
) extends Module {
  val io = IO(new Bundle {
    val block_intra_step = Input(UInt(mrp.addrWidth.W))
    val block_intra_count = Input(UInt(mrp.addrWidth.W))
    val in = Flipped(Decoupled(new BlockStridedRqDescriptor(mrp)))
    val out = Decoupled(new GenericMemoryRequest(mrp))
  })

  // the implementation essentially consists of two sequence generators that
  // correspond to two nested loops. the outer_sg traverses  blocks,
  // while the inner_sh traverses within the block.

  val outer_sg = Module(
    new MultiSeqGen(
      new MultiSeqGenParams(
        w = mrp.addrWidth,
        a = mrp.addrWidth
      )
    )
  ).io

  val inner_sg = Module(
    new MultiSeqGen(
      new MultiSeqGenParams(
        w = mrp.addrWidth,
        a = mrp.addrWidth
      )
    )
  ).io

  outer_sg.in.valid := io.in.valid
  io.in.ready := outer_sg.in.ready
  outer_sg.in.bits.init := io.in.bits.base
  outer_sg.in.bits.count := io.in.bits.block_count
  outer_sg.in.bits.step := io.in.bits.block_step

  val outer_seq = FPGAQueue(outer_sg.out, 2)
  inner_sg.in.valid := outer_seq.valid
  outer_seq.ready := inner_sg.in.ready
  inner_sg.in.bits.init := outer_seq.bits
  inner_sg.in.bits.count := io.block_intra_count
  inner_sg.in.bits.step := io.block_intra_step

  val inner_seq = FPGAQueue(inner_sg.out, 2)
  io.out.valid := inner_seq.valid
  inner_seq.ready := io.out.ready
  io.out.bits.channelID := chanID.U
  io.out.bits.isWrite := writeEn.B
  io.out.bits.addr := inner_seq.bits
  io.out.bits.numBytes := io.block_intra_step
  io.out.bits.metaData := 0.U
}
