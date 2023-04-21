// Copyright (c) 2018 Norwegian University of Science and Technology (NTNU)
//
// BSD v3 License
//
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// * Redistributions of source code must retain the above copyright notice, this
//   list of conditions and the following disclaimer.
//
// * Redistributions in binary form must reproduce the above copyright notice,
//   this list of conditions and the following disclaimer in the documentation
//   and/or other materials provided with the distribution.
//
// * Neither the name of [project] nor the names of its
//   contributors may be used to endorse or promote products derived from
//   this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

#include <cassert>
#include <vector>
#include <iomanip>
#include <iostream>
#include "BitSerialMatMulAccelDriver.hpp"
#include "gemmbitserial/gemmbitserial.hpp"

#define min(x, y) (x < y ? x : y)
#define max(x, y) (x > y ? x : y)
#define INVALID_CACHE_ENTRY (uint64_t) - 1

// TODO:
// - define own context allocator for the accelerator, including
// alignment requirements for lhs/rhs.
// - do not use entire GEMMContext, only need shape
// - (idea) add lockstep execution mode for debug purposes?

class BitSerialMatMulExecutor
{
public:
  BitSerialMatMulExecutor(
      gemmbitserial::GEMMContext &shape,
      BitSerialMatMulAccelDriver *acc,
      WrapperRegDriver *platform)
  {
    m_shape = shape;
    m_acc = acc;
    m_hwcfg = m_acc->hwcfg();
    m_platform = platform;
    m_bytes_to_fetch = 0;
    m_bytes_to_write = 0;
    // TODO verify alignment etc for instantiated hardware dimensions
    // allocate accelerator memory for given shape
    m_accelLHS = m_platform->allocAccelBuffer(lhsBytes());
    m_accelRHS = m_platform->allocAccelBuffer(rhsBytes());
    m_accelRes = m_platform->allocAccelBuffer(resBytes());
    // indicate that nothing is cached on-chip yet
    for (unsigned int i = 0; i < FETCHEXEC_TOKENS; i++)
    {
      m_cached_lhs.push_back(INVALID_CACHE_ENTRY);
      m_cached_rhs.push_back(INVALID_CACHE_ENTRY);
    }
    // build schedule for each stage based on shape
    build_schedule_trivial();
    m_acc->reset();
    m_acc->set_stage_enables(1, 1, 1);
  }

  ~BitSerialMatMulExecutor()
  {
    // deinitialize allocated memory
    m_platform->deallocAccelBuffer(m_accelLHS);
    m_platform->deallocAccelBuffer(m_accelRHS);
    m_platform->deallocAccelBuffer(m_accelRes);
  }

  void setLHS(gemmbitserial::BitSerialMatrix from)
  {
    assert(m_shape.lhs.nrows_a == from.nrows_a);
    assert(m_shape.lhs.nbits == from.nbits);
    // copy host -> accel
    m_platform->copyBufferHostToAccel(from.data, m_accelLHS, lhsBytes());
    // invalidate cache
    for (unsigned int i = 0; i < FETCHEXEC_TOKENS; i++)
    {
      m_cached_lhs[i] = INVALID_CACHE_ENTRY;
    }
  }

  void setRHS(gemmbitserial::BitSerialMatrix from)
  {
    assert(m_shape.rhs.nrows_a == from.nrows_a);
    assert(m_shape.rhs.nbits == from.nbits);
    // copy host -> accel
    m_platform->copyBufferHostToAccel(from.data, m_accelRHS, rhsBytes());
    // invalidate cache
    for (unsigned int i = 0; i < FETCHEXEC_TOKENS; i++)
    {
      m_cached_rhs[i] = INVALID_CACHE_ENTRY;
    }
  }

  void getRes(ResultType *to)
  {
    // result alignment
    size_t alignedResElems = m_shape.rhs.nrows_a * m_shape.lhs.nrows_a;
    ResultType *host_res = new ResultType[alignedResElems];
    // copy aligned result into host buffer
    m_platform->copyBufferAccelToHost(m_accelRes, host_res, resBytes());
    // copy all real data (non-alignment) parts of result
    const size_t bpr = m_shape.lhs.nrows * sizeof(ResultType);
    for (size_t i = 0; i < m_shape.rhs.nrows; i++)
    {
      memcpy(
          &to[i * m_shape.lhs.nrows], &host_res[i * m_shape.lhs.nrows_a], bpr);
    }
    delete[] host_res;
  }

  void run()
  {
    m_acc->set_stage_enables(0, 0, 0);

    // start the cycle counter
    m_acc->perf_set_cc_enable(true);
    // enable all stages
    m_acc->set_stage_enables(1, 1, 1);
    // run the generated schedule -- keep pushing operands until all generated
    // instructions have been pushed

    m_acc->do_instruction(icfg);

    // wait until the result stage has no instructions std::left (= all finished)

    while (!m_acc->allFinished())
      ;

    // disable all stages
    m_acc->set_stage_enables(0, 0, 0);
    // stop the cycle counter
    m_acc->perf_set_cc_enable(false);
    m_cycles = m_acc->perf_get_cc();

    // Reset instruction generators for next time
    m_acc->reset_generators();
    // fetch the number of cycles spent in different states for each stage
    updateFetchStateCounters();
    updateExecStateCounters();
    updateResultStateCounters();
  }

  size_t lhsBytes() const
  {
    return m_shape.lhs.wordsPerBitplane() * m_shape.lhs.nbits * sizeof(PackedBitGroupType);
  }

  size_t rhsBytes() const
  {
    return m_shape.rhs.wordsPerBitplane() * m_shape.rhs.nbits * sizeof(PackedBitGroupType);
  }

  size_t resBytes() const
  {
    return m_shape.lhs.nrows_a * m_shape.rhs.nrows_a * sizeof(ResultType);
  }

  // performance counters and related performance reporting functions
  // ===========================================================================
  float getNanosecondsPerCycle() const
  {
    return 1000.0 / m_acc->fclk_MHz();
  }

  float getLastRuntimeCycles() const
  {
    return (float)m_cycles;
  }

  float getLastRuntimeNanoseconds() const
  {
    return getLastRuntimeCycles() * getNanosecondsPerCycle();
  }

  float getWorkloadOpCount(bool inclPadding = true) const
  {
    if (inclPadding)
    {
      return 2 * m_shape.lhs.nrows_a * m_shape.rhs.nrows_a * m_shape.lhs.ncols_a;
    }
    else
    {
      return 2 * m_shape.lhs.nrows * m_shape.rhs.nrows * m_shape.lhs.ncols;
    }
  }

  float getWorkloadBinaryOpCount(bool inclPadding = true) const
  {
    return getWorkloadOpCount(inclPadding) * m_shape.lhs.nbits * m_shape.rhs.nbits;
  }

  float getLastRunBinaryGOPS(bool inclPadding = true) const
  {
    // giga-ops per second = ops per nanosecond
    return getWorkloadBinaryOpCount(inclPadding) / getLastRuntimeNanoseconds();
  }

  float getHWPeakBinaryOpsPerCycle() const
  {
    return 2 * m_hwcfg.dpaDimLHS * m_hwcfg.dpaDimRHS * m_hwcfg.dpaDimCommon;
  }

  float getHWPeakBinaryGOPS() const
  {
    return getHWPeakBinaryOpsPerCycle() / getNanosecondsPerCycle();
  }

  size_t getHWBufSizeLHS() const
  {
    return m_hwcfg.dpaDimLHS * m_hwcfg.lhsEntriesPerMem * m_hwcfg.dpaDimCommon / 8;
  }

  size_t getHWBufSizeRHS() const
  {
    return m_hwcfg.dpaDimRHS * m_hwcfg.rhsEntriesPerMem * m_hwcfg.dpaDimCommon / 8;
  }

  size_t getHWBufSize() const
  {
    return getHWBufSizeLHS() + getHWBufSizeRHS();
  }

  float getWorkloadReadOI() const
  {
    return getWorkloadBinaryOpCount(true) / (lhsBytes() + rhsBytes());
  }

  float getWorkloadWriteOI() const
  {
    return getWorkloadBinaryOpCount(true) / (resBytes());
  }

  float getWorkloadOI() const
  {
    return getWorkloadBinaryOpCount(true) / (lhsBytes() + rhsBytes() + resBytes());
  }

  // read bandwidth in bytes per cycle
  float getHWReadBW() const
  {
    return m_hwcfg.readChanWidth / 8;
  }

  // write bandwidth in bytes per cycle
  float getHWWriteBW() const
  {
    return m_hwcfg.writeChanWidth / 8;
  }

  float getHWCompBoundReadOI() const
  {
    return getHWPeakBinaryOpsPerCycle() / getHWReadBW();
  }

  float getHWCompBoundWriteOI() const
  {
    return getHWPeakBinaryOpsPerCycle() / getHWWriteBW();
  }

  void printPerfSummary()
  {
    std::cout << "Performance Summary ====================================" << std::endl;
    std::cout << "Total workload: " << getWorkloadBinaryOpCount(true) << " binary ops" << std::endl;
    std::cout << "Actual workload: " << getWorkloadBinaryOpCount(false) << " binary ops ";
    std::cout << "(" << 100 * getWorkloadBinaryOpCount(false) / getWorkloadBinaryOpCount(true) << "%)" << std::endl;
    std::cout << "Input matrix bytes: LHS " << lhsBytes() << " RHS " << rhsBytes() << std::endl;
    std::cout << "Result matrix bytes: " << resBytes() << std::endl;
    std::cout << "HW input matrix buffer bytes: " << getHWBufSize() << std::endl;
    std::cout << "HW peak perf: " << getHWPeakBinaryGOPS() << " binary GOPS" << std::endl;
    std::cout << "HW fclk: " << m_acc->fclk_MHz() << " MHz" << std::endl;
    std::cout << "Runtime: " << getLastRuntimeCycles() << " cycles, ";
    std::cout << getLastRuntimeNanoseconds() << " ns" << std::endl;
    std::cout << "Achieved: " << getLastRunBinaryGOPS() << " binary GOPS (";
    std::cout << 100 * getLastRunBinaryGOPS() / getHWPeakBinaryGOPS() << "%)" << std::endl;
    std::cout << "Workload OI read: " << getWorkloadReadOI();
    std::cout << " write: " << getWorkloadWriteOI() << std::endl;
    std::cout << "HW comp-bound OI read: " << getHWCompBoundReadOI();
    std::cout << " write: " << getHWCompBoundWriteOI() << std::endl;
    std::cout << "========================================================" << std::endl;
  }

  void printPerfDetails()
  {
    int colwidth = 11;
    std::cout << "Cycles Spent in ControllerState ========================" << std::endl;
    std::cout << std::left << std::setw(colwidth) << "Stage";
    std::cout << std::left << std::setw(colwidth) << "csGetCmd";
    std::cout << std::left << std::setw(colwidth) << "csRun";
    std::cout << std::left << std::setw(colwidth) << "csSend";
    std::cout << std::left << std::setw(colwidth) << "csReceive" << std::endl;
    // print fetch stage state cycles breakdown
    std::cout << std::left << std::setw(colwidth) << "Fetch";
    for (int i = 0; i < N_CTRL_STATES; i++)
    {
      std::cout << std::left << std::setw(colwidth) << m_fetch_cstate_cycles[i];
    }
    std::cout << std::endl;
    // print exec stage state cycles breakdown
    std::cout << std::left << std::setw(colwidth) << "Execute";
    for (int i = 0; i < N_CTRL_STATES; i++)
    {
      std::cout << std::left << std::setw(colwidth) << m_exec_cstate_cycles[i];
    }
    std::cout << std::endl;
    // print result stage state cycles breakdown
    std::cout << std::left << std::setw(colwidth) << "Result";
    for (int i = 0; i < N_CTRL_STATES; i++)
    {
      std::cout << std::left << std::setw(colwidth) << m_result_cstate_cycles[i];
    }
    std::cout << std::endl;

    std::cout << "Memory System ==========================================" << std::endl;
    std::cout << "DRAM reads: " << m_bytes_to_fetch << " bytes" << std::endl;
    float rd_bw = (float)m_bytes_to_fetch / getLastRuntimeCycles();
    float rd_fetchact_bw = (float)m_bytes_to_fetch / m_fetch_cstate_cycles[csRun];
    std::cout << "HW peak rd bandwidth: " << getHWReadBW() << " bytes/cycle" << std::endl;
    std::cout << "Effective rd bandwidth: " << rd_bw << " bytes/cycle (";
    std::cout << 100 * rd_bw / getHWReadBW() << "%)" << std::endl;
    std::cout << "Fetch rd bandwidth: " << rd_fetchact_bw << " bytes/cycle (";
    std::cout << 100 * rd_fetchact_bw / getHWReadBW() << "%)" << std::endl;

    std::cout << "DRAM writes: " << m_bytes_to_write << " bytes" << std::endl;
    float wr_bw = (float)m_bytes_to_write / getLastRuntimeCycles();
    float wr_resact_bw = (float)m_bytes_to_write / m_result_cstate_cycles[csRun];
    std::cout << "HW peak wr bandwidth: " << getHWWriteBW() << " bytes/cycle" << std::endl;
    std::cout << "Effective wr bandwidth: " << wr_bw << " bytes/cycle (";
    std::cout << 100 * wr_bw / getHWWriteBW() << "%)" << std::endl;
    std::cout << "Result wr bandwidth: " << wr_resact_bw << " bytes/cycle (";
    std::cout << 100 * wr_resact_bw / getHWWriteBW() << "%)" << std::endl;

    float exec_eff = getWorkloadBinaryOpCount(true) / ((m_exec_cstate_cycles[csRun] * getHWPeakBinaryOpsPerCycle()));
    std::cout << "Execute stage efficiency: " << 100 * exec_eff << "%" << std::endl;
    std::cout << "========================================================" << std::endl;
  }

protected:
  uint32_t m_cycles;
  uint32_t m_fetch_cstate_cycles[N_CTRL_STATES];
  uint32_t m_exec_cstate_cycles[N_CTRL_STATES];
  uint32_t m_result_cstate_cycles[N_CTRL_STATES];
  uint32_t m_bytes_to_fetch, m_bytes_to_write;
  InstructionCfg icfg;

  gemmbitserial::GEMMContext m_shape;
  BitSerialMatMulAccelDriver *m_acc;
  WrapperRegDriver *m_platform;
  HardwareCfg m_hwcfg;

  void *m_accelLHS;
  void *m_accelRHS;
  void *m_accelRes;

  // keep track of what we have in the on-chip memory to avoid re-fetching
  std::vector<uint64_t> m_cached_lhs, m_cached_rhs;

  // whether all instruction execution has finished
  // = no instrs in result queue and all instrs pushed to queue

  void updateFetchStateCounters()
  {
    for (int i = 0; i < N_CTRL_STATES; i++)
    {
      m_fetch_cstate_cycles[i] = m_acc->perf_fetch_stats((ControllerState)i);
    }
  }

  void updateExecStateCounters()
  {
    for (int i = 0; i < N_CTRL_STATES; i++)
    {
      m_exec_cstate_cycles[i] = m_acc->perf_exec_stats((ControllerState)i);
    }
  }

  void updateResultStateCounters()
  {
    cout << "Updates result state counters" << endl;
    for (int i = 0; i < N_CTRL_STATES; i++)
    {
      m_result_cstate_cycles[i] = m_acc->perf_result_stats((ControllerState)i);
    }
  }

  const size_t lhs_eff_rows()
  {
    return m_shape.lhs.nrows_a;
  }

  const size_t rhs_eff_rows()
  {
    return m_shape.rhs.nrows_a;
  }

  void build_schedule_trivial()
  {
    HardwareCfg cfg = m_acc->hwcfg();
    const uint32_t dpa_y = cfg.dpaDimLHS;    // DPA Y dimension
    const uint32_t dpa_x = cfg.dpaDimRHS;    // DPA X dimension
    const uint32_t dpa_z = cfg.dpaDimCommon; // DPA z dimension (64)
    const uint32_t dpa_z_bytes = dpa_z / 8;
    gemmbitserial::BitSerialMatrix lhs = m_shape.lhs; // Matrix for lhs and rhs
    gemmbitserial::BitSerialMatrix rhs = m_shape.rhs;
    int current_bram_region = 0;
    const size_t bram_regions = FETCHEXEC_TOKENS;
    int current_resmem_region = 0;
    const size_t resmem_regions = EXECRES_TOKENS;

    assert(dpa_z >= cfg.readChanWidth);
    assert(dpa_z % cfg.readChanWidth == 0);
    const size_t exec_to_fetch_width_ratio = dpa_z / cfg.readChanWidth;

    const uint32_t lhs_l0_per_bram = cfg.lhsEntriesPerMem / bram_regions;
    const uint32_t rhs_l0_per_bram = cfg.rhsEntriesPerMem / bram_regions;

    const size_t lhs_bytes_per_l0 = dpa_y * dpa_z / 8;
    const size_t rhs_bytes_per_l0 = dpa_x * dpa_z / 8;
    // L1 tile. min 1 L0 tile, maximum L0 tiles that fit into OCM.
    // only tiled along the common dimension
    const size_t l0_per_stripe = lhs.ncols_a / dpa_z;
    const size_t lhs_l0_per_l1 = min(lhs_l0_per_bram, l0_per_stripe);
    const size_t lhs_bytes_per_l1 = lhs_l0_per_l1 * lhs_bytes_per_l0;
    const size_t rhs_l0_per_l1 = min(rhs_l0_per_bram, l0_per_stripe);
    const size_t rhs_bytes_per_l1 = rhs_l0_per_l1 * rhs_bytes_per_l0;
    // L2 tile. min 1 L1 tile, maximum L1 tiles that fit into OCM.
    // tiled along either:
    // only common dimension if rows are wider than BRAM (hw-bound)
    // only lhs/rhs dimension if rows are smaller than BRAM (sw-bound)
    const size_t lhs_max_l1_hw = lhs_l0_per_bram / lhs_l0_per_l1;
    const size_t lhs_max_l1_sw = lhs_eff_rows() / dpa_y;
    const size_t lhs_l1_per_l2 = min(lhs_max_l1_hw, lhs_max_l1_sw);
    const size_t lhs_bytes_per_l2 = lhs_l1_per_l2 * lhs_bytes_per_l1;
    const size_t rhs_max_l1_hw = rhs_l0_per_bram / rhs_l0_per_l1;
    const size_t rhs_max_l1_sw = rhs_eff_rows() / dpa_x;
    const size_t rhs_l1_per_l2 = min(rhs_max_l1_hw, rhs_max_l1_sw);
    const size_t rhs_bytes_per_l2 = rhs_l1_per_l2 * rhs_bytes_per_l1;
    // total L2 tile counts in the matrices
    // TODO use the minimum-sized of LHS or RHS BRAM capacity here
    const size_t z_l2_per_matrix = max(1, (lhs.ncols_a / dpa_z) / lhs_l0_per_bram); // l1 tiles per z direction
    // the L2 tile count obtained by total_bytes / L2_tiles does not have any
    // axis information (e.g. may be product of LHS and Z tiling)
    // so divide by the common dimension tiling factor
    const size_t lhs_l2_per_matrix = (lhsBytes() / lhs_bytes_per_l2) / z_l2_per_matrix; // l1 tiles in y direction per l2 tile
    const size_t rhs_l2_per_matrix = (rhsBytes() / rhs_bytes_per_l2) / z_l2_per_matrix; // l1 tiles in x direction per l2 tile

    // TODO l1 tile size is not guaranteed to evenly divide the matrix
    // due to partial tiles, and same with l2 tile size. need to handle this
    // either by smart padding/alignment during allocation, or changing tile
    // upper bounds.
    assert(lhsBytes() % lhs_bytes_per_l2 == 0);
    assert(rhsBytes() % rhs_bytes_per_l2 == 0);

    // ensure the LHS rows are integer multiples of the DPA dims
    assert(0 == lhs_eff_rows() % dpa_y);
    assert(0 == rhs_eff_rows() % dpa_x);
    assert(0 == lhs.ncols_a % dpa_z);
    // Binary matrix
    assert(lhs.nbits == 1 && rhs.nbits == 1);

    const uint64_t fetch_base_lhs = (uint64_t)m_accelLHS;
    const uint64_t fetch_base_rhs = (uint64_t)m_accelRHS;
    uint64_t res_base = (uint64_t)m_accelRes;

    icfg = {
        // Fetch
        .fetch_dram_base_rhs = (void *)fetch_base_rhs,
        .fetch_dram_base_lhs = (void *)fetch_base_lhs,
        .dram_block_offset_bytes = (uint32_t)(lhs.ncols_a / 8),
        .dram_block_size_bytes = (uint32_t)lhs_l0_per_l1 * dpa_z_bytes,
        .dram_block_count = (uint32_t)(lhs_bytes_per_l2 / (lhs_l0_per_l1 * dpa_z_bytes)),
        .tiles_per_row = (uint32_t)(lhs_l0_per_l1 * exec_to_fetch_width_ratio),
        // Exec
        .num_tiles = (uint32_t)lhs_l0_per_l1,
        .shift_amount = 0,
        .negate = 0,

        // Res
        .res_dram_base = (void *)res_base,
        .dram_skip = lhs_eff_rows() * sizeof(ResultType),
        .wait_complete_bytes = resBytes(),

        .z_l2_per_matrix = (uint32_t)z_l2_per_matrix,
        .lhs_l2_per_matrix = (uint32_t)lhs_l2_per_matrix,
        .rhs_l2_per_matrix = (uint32_t)rhs_l2_per_matrix,
        .lhs_bytes_per_l2 = (uint32_t)lhs_bytes_per_l2,
        .rhs_bytes_per_l2 = (uint32_t)rhs_bytes_per_l2,
        .dpa_z_bytes = dpa_z_bytes,
        .lhs_l0_per_l1 = (uint32_t)lhs_l0_per_l1,
        .rhs_l0_per_l1 = (uint32_t)rhs_l0_per_l1,
        .lhs_l1_per_l2 = (uint32_t)lhs_l1_per_l2,
        .rhs_l1_per_l2 = (uint32_t)rhs_l1_per_l2,
        .nrows_a = (uint32_t)lhs_eff_rows(),
    };

    for(int lhs_l2 = 0; lhs_l2 < lhs_l2_per_matrix; lhs_l2++) {
      for(int rhs_l2 = 0; rhs_l2 < rhs_l2_per_matrix; rhs_l2++) {
        for(int z_l2 = 0; z_l2 < z_l2_per_matrix; z_l2++) {
          void * lhs_dram_base = (void *)(fetch_base_lhs + lhs_l2*z_l2_per_matrix*lhs_bytes_per_l2 + z_l2 * lhs_l0_per_l1 * dpa_z_bytes);
          void * rhs_dram_base = (void *)(fetch_base_rhs + rhs_l2*z_l2_per_matrix*rhs_bytes_per_l2 + z_l2 * rhs_l0_per_l1 * dpa_z_bytes);
          // only issue fetch if not already in cache
          if(m_cached_lhs[current_bram_region] != (uint64_t) lhs_dram_base) {
            m_bytes_to_fetch += lhs_bytes_per_l2;
            m_cached_lhs[current_bram_region] = (uint64_t) lhs_dram_base;
          }

          // only issue fetch if not already in cache
          if(m_cached_rhs[current_bram_region] != (uint64_t) rhs_dram_base) {
            m_bytes_to_fetch += rhs_bytes_per_l2;
            m_cached_rhs[current_bram_region] = (uint64_t) rhs_dram_base;
          }

          // process combinations of L1 tiles within the L2 tile
          for(int lhs_l1 = 0; lhs_l1 < lhs_l1_per_l2; lhs_l1++) {
            for(int rhs_l1 = 0; rhs_l1 < rhs_l1_per_l2; rhs_l1++) {
              if(z_l2 == z_l2_per_matrix - 1) {
                m_bytes_to_write += m_hwcfg.dpaDimLHS * m_hwcfg.dpaDimRHS * sizeof(ResultType);
                current_resmem_region = current_resmem_region < resmem_regions-1 ? current_resmem_region + 1 : 0;
              }
            }
          }
          current_bram_region = current_bram_region < bram_regions-1 ? current_bram_region + 1 : 0;
        }
      }
    }
  }
};