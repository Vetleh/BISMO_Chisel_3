package bismo

import chisel3._
import chiseltest._
import org.scalatest._
import chisel3.experimental.BundleLiterals._
import BISMOTestHelper._
import fpgatidbits.PlatformWrapper._

// TODO fix input params to a more general state
class TestFetchInterconnect extends FreeSpec with ChiselScalatestTester {
  val numLHSMems = 5
  val numRHSMems = 5
  val numAddrBits = 10
  val mrp = PYNQZ1Params.toMemReqParams()
  val numChans = 1
  val fetchStageParams =
    new FetchStageParams(numLHSMems, numRHSMems, numAddrBits, mrp, numChans)

  "MultiSeqGen test" in {
    test(new FetchInterconnect(fetchStageParams)) { c =>
      val r = scala.util.Random
      // number of writes to test
      val num_writes = 100
      // number of instantiated nodes
      val num_nodes = c.myP.numNodes
      // bitwidth of packet data
      val dbits = c.myP.mrp.dataWidth
      // maximum address inside BRAM
      val maxaddr = (1 << c.myP.numAddrBits) - 1

      def randomNoValidWait(max: Int = num_nodes + 1) = {
        val numNoValidCycles = r.nextInt(max + 1)
        c.io.in.valid.poke(0.B)
        for (cyc <- 0 until numNoValidCycles) {
          c.clock.step(1)
          // all write enables should be zero
          c.io.node_out.map(x => x.writeEn.expect(0.B))
        }
      }

      def checkNodeMemStatus(
          golden_valid: Seq[Int],
          golden_id: Seq[Int],
          golden_addr: Seq[Int],
          golden_data: Seq[BigInt]
      ) = {
        for (n <- 0 until num_nodes) {
          if (golden_valid(n) == 1 && n == golden_id(n)) {
            c.io.node_out(n).writeEn.expect(1.B)
            c.io.node_out(n).addr.expect(golden_addr(n).U)
            c.io.node_out(n).writeData.expect(golden_data(n).U)
          } else {
            c.io.node_out(n).writeEn.expect(0.B)
          }
        }
      }

      randomNoValidWait()
      var exp_valid = (1 to num_nodes).map(x => 0).toList
      var exp_id = (1 to num_nodes).map(x => 0).toList
      var exp_addr = (1 to num_nodes).map(x => 0).toList
      var exp_data = (1 to num_nodes).map(x => BigInt(0)).toList

      for (i <- 1 to num_writes) {
        val test_data = BISMOTestHelper.randomIntVector(dbits, 1, false)
        val test_data_bigint = scala.math.BigInt.apply(test_data.mkString, 2)
        val test_id = r.nextInt(num_nodes + 1)
        val test_addr = r.nextInt(maxaddr + 1)
        c.io.in.bits.data.poke(test_data_bigint.U)
        c.io.in.bits.id.poke(test_id.U)
        c.io.in.bits.addr.poke(test_addr.U)
        c.io.in.valid.poke(1.B)
        // update expected state
        exp_valid = List(1) ++ exp_valid.dropRight(1)
        exp_id = List(test_id) ++ exp_id.dropRight(1)
        exp_addr = List(test_addr) ++ exp_addr.dropRight(1)
        exp_data = List(test_data_bigint) ++ exp_data.dropRight(1)
        c.clock.step(1)
        c.io.in.valid.poke(0.B)
        for (ws <- 0 to test_id) {
          checkNodeMemStatus(exp_valid, exp_id, exp_addr, exp_data)
          exp_valid = List(0) ++ exp_valid.dropRight(1)
          exp_id = List(test_id) ++ exp_id.dropRight(1)
          exp_addr = List(test_addr) ++ exp_addr.dropRight(1)
          exp_data = List(test_data_bigint) ++ exp_data.dropRight(1)
          c.clock.step(1)
        }
      }
    }
  }
}
