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

#ifndef BitSerialMatMulAccelDriver_H
#define BitSerialMatMulAccelDriver_H

#include <cassert>
#include <unistd.h>
#include "platform.h"
#include "BitSerialMatMulAccel.hpp"
#include <iostream>
#include "gemmbitserial/gemmbitserial.hpp"

#define CMDFIFO_CAP 16
#define FETCHEXEC_TOKENS 2
#define EXECRES_TOKENS 2
#define N_CTRL_STATES 4
#define FETCH_ADDRALIGN 64
#define FETCH_SIZEALIGN 8

#define max(x, y) (x > y ? x : y)
#define FETCH_ALIGN max(FETCH_ADDRALIGN, FETCH_SIZEALIGN)



typedef enum
{
  csGetCmd = 0,
  csRun,
  csSend,
  csReceive
} ControllerState;

typedef struct
{
  // Fetch
  void *fetch_dram_base_rhs;
  void *fetch_dram_base_lhs;
  uint32_t dram_block_offset_bytes;
  uint32_t dram_block_size_bytes;
  uint32_t dram_block_count;
  uint32_t tiles_per_row;

  // Exec
  uint32_t num_tiles;
  uint32_t shift_amount;
  uint32_t negate;

  // Res
  void *res_dram_base;
  uint64_t dram_skip;
  uint64_t wait_complete_bytes;

  uint32_t z_l2_per_matrix;
  uint32_t lhs_l2_per_matrix;
  uint32_t rhs_l2_per_matrix;
  uint32_t lhs_bytes_per_l2;
  uint32_t rhs_bytes_per_l2;
  uint32_t dpa_z_bytes;
  uint32_t lhs_l0_per_l1;
  uint32_t rhs_l0_per_l1;
  uint32_t lhs_l1_per_l2;
  uint32_t rhs_l1_per_l2;
  uint32_t nrows_a;
} InstructionCfg;

typedef struct
{
  uint32_t accWidth;
  uint32_t cmdQueueEntries;
  uint32_t dpaDimCommon;
  uint32_t dpaDimLHS;
  uint32_t dpaDimRHS;
  uint32_t lhsEntriesPerMem;
  uint32_t maxShiftSteps;
  uint32_t readChanWidth;
  uint32_t rhsEntriesPerMem;
  uint32_t writeChanWidth;
} HardwareCfg;

typedef uint64_t PackedBitGroupType;
typedef int32_t ResultType;

class BitSerialMatMulAccelDriver
{
public:
  BitSerialMatMulAccelDriver(WrapperRegDriver *platform)
  {
    m_platform = platform;
    m_accel = new BitSerialMatMulAccel(m_platform);
    m_fclk = 200.0;
    update_hw_cfg();
    measure_fclk();
  }
  ~BitSerialMatMulAccelDriver()
  {
  }

  void measure_fclk()
  {
    // if (m_platform->platformID() != "EmuDriver")
    // {
    //   uint32_t cc_start = perf_get_cc();
    //   perf_set_cc_enable(true);
    //   // sleep for one second of CPU time
    //   usleep(1000000);
    //   perf_set_cc_enable(false);
    //   uint32_t cc_end = perf_get_cc();
    //   // million ticks per second = fclk in MHz
    //   m_fclk = (float)(cc_end - cc_start) / 1000000.0;
    // }
  }

  float fclk_MHz() const
  {
    return m_fclk;
  }

  // allocate a GEMMContext compliant with the accelerator size
  gemmbitserial::GEMMContext allocGEMMContext(
      uint64_t lhsRows, uint64_t depth, uint64_t rhsRows,
      uint64_t lhsBits, uint64_t rhsBits,
      bool lhsSigned, bool rhsSigned)
  {
    const uint64_t regblock_lhs = m_cfg.dpaDimLHS;
    const uint64_t regblock_d = FETCH_ALIGN / sizeof(PackedBitGroupType);
    const uint64_t regblock_rhs = m_cfg.dpaDimRHS;
    const uint64_t cacheBits = 1;

    return gemmbitserial::allocGEMMContext_base(
        lhsRows, depth, rhsRows, lhsBits, rhsBits, lhsSigned, rhsSigned,
        regblock_lhs, regblock_d, regblock_rhs, cacheBits);
  }

  // enable/disable the cycle counter
  // cleared on rising edge (i.e. 0->1 transition)
  // increments by 1 every cycle while enabled
  void perf_set_cc_enable(bool e)
  {
    m_accel->set_perf_cc_enable(e ? 1 : 0);
  }

  // return cycle count
  uint32_t perf_get_cc()
  {
    return m_accel->get_perf_cc();
  }

  // get the number of cycles that elapsed in a given state
  // for each controller

  uint32_t perf_fetch_stats(ControllerState s)
  {
    m_accel->set_perf_prf_fetch_sel((uint32_t)s);
    return m_accel->get_perf_prf_fetch_count();
  }

  uint32_t perf_exec_stats(ControllerState s)
  {
    m_accel->set_perf_prf_exec_sel((uint32_t)s);
    return m_accel->get_perf_prf_exec_count();
  }

  uint32_t perf_result_stats(ControllerState s)
  {
    cout << "Controller state: " << s << endl;
    m_accel->set_perf_prf_res_sel((uint32_t)s);
    return m_accel->get_perf_prf_res_count();
  }

  static void printInstruction(InstructionCfg r)
  {
    // TODO fix this for debuging
  }

  const size_t get_lhs_total_BRAM_bytes()
  {
    return m_cfg.dpaDimLHS * m_cfg.lhsEntriesPerMem * m_cfg.dpaDimCommon / 8;
  }

  const size_t get_rhs_total_BRAM_bytes()
  {
    return m_cfg.dpaDimRHS * m_cfg.rhsEntriesPerMem * m_cfg.dpaDimCommon / 8;
  }

  const size_t get_fetch_nodes_per_group()
  {
    return (m_cfg.dpaDimLHS + m_cfg.dpaDimRHS);
  }

  const size_t get_fetch_first_lhs_id()
  {
    return 0;
  }

  const size_t get_fetch_first_rhs_id()
  {
    return m_cfg.dpaDimLHS;
  }

  // get command counts in FIFOs
  const uint32_t fetch_opcount()
  {
    return m_accel->get_fetch_op_count();
  }
  const uint32_t exec_opcount()
  {
    return m_accel->get_exec_op_count();
  }
  const uint32_t res_opcount()
  {
    return m_accel->get_result_op_count();
  }

  // check whether it's possible to write a new element into a queue
  const bool instruction_generator_ready()
  {
    return m_accel->get_ins_ready();
  }

  // reset the accelerator
  // TODO how does this work in practise for MMIO?
  void reset()
  {
    m_platform->writeReg(0, 1);
    m_platform->writeReg(0, 0);
  }

  // enable/disable the execution of each stage
  void set_stage_enables(const int fetch, const int exec, const int result)
  {
    m_accel->set_fetch_enable(fetch);
    m_accel->set_exec_enable(exec);
    m_accel->set_result_enable(result);
  }

  // TODO push instruction generation stuff
  void do_instruction(InstructionCfg icfg)
  {
    m_accel->set_ins_bits_dpa_z_bytes(icfg.dpa_z_bytes);
    m_accel->set_ins_bits_nrows_a(icfg.nrows_a);
    m_accel->set_ins_bits_rhs_bytes_per_l2(icfg.rhs_bytes_per_l2);
    m_accel->set_ins_bits_lhs_bytes_per_l2(icfg.lhs_bytes_per_l2);
    m_accel->set_ins_bits_z_l2_per_matrix(icfg.z_l2_per_matrix);
    m_accel->set_ins_bits_rhs_l2_per_matrix(icfg.rhs_l2_per_matrix);
    m_accel->set_ins_bits_lhs_l2_per_matrix(icfg.lhs_l2_per_matrix);
    m_accel->set_ins_bits_rhs_l1_per_l2(icfg.rhs_l1_per_l2);
    m_accel->set_ins_bits_lhs_l1_per_l2(icfg.lhs_l1_per_l2);
    m_accel->set_ins_bits_rhs_l0_per_l1(icfg.rhs_l0_per_l1);
    m_accel->set_ins_bits_lhs_l0_per_l1(icfg.lhs_l0_per_l1);
    m_accel->set_ins_bits_wait_complete_bytes(icfg.wait_complete_bytes);
    m_accel->set_ins_bits_dram_skip(icfg.dram_skip);
    m_accel->set_ins_bits_dram_base((uint64_t) icfg.res_dram_base);
    m_accel->set_ins_bits_negate(icfg.negate);
    m_accel->set_ins_bits_shiftAmount(icfg.shift_amount);
    m_accel->set_ins_bits_numTiles(icfg.num_tiles);
    m_accel->set_ins_bits_tiles_per_row(icfg.tiles_per_row);
    m_accel->set_ins_bits_dram_block_count(icfg.dram_block_count);
    m_accel->set_ins_bits_dram_block_size_bytes(icfg.dram_block_size_bytes);
    m_accel->set_ins_bits_dram_block_offset_bytes(icfg.dram_block_offset_bytes);
    m_accel->set_ins_bits_dram_base_rhs((uint64_t) icfg.fetch_dram_base_rhs);
    m_accel->set_ins_bits_dram_base_lhs((uint64_t) icfg.fetch_dram_base_lhs);
    m_accel->set_ins_valid(1);
  }

  bool allFinished()
  {
    return m_accel->get_result_op_count() == 0 && m_accel->get_exec_op_count() == 0 && m_accel->get_fetch_op_count() == 0;
  }

  // get the instantiated hardware config
  HardwareCfg hwcfg() const
  {
    return m_cfg;
  }

  void reset_generators()
  {
    m_accel->set_ins_valid(0);
  }

  // print a summary of the hardware config
  void print_hwcfg_summary() const
  {
    cout << "accWidth = " << m_cfg.accWidth << endl;
    cout << "cmdQueueEntries = " << m_cfg.cmdQueueEntries << endl;
    cout << "dpaDimCommon = " << m_cfg.dpaDimCommon << endl;
    cout << "dpaDimLHS = " << m_cfg.dpaDimLHS << endl;
    cout << "dpaDimRHS = " << m_cfg.dpaDimRHS << endl;
    cout << "lhsEntriesPerMem = " << m_cfg.lhsEntriesPerMem << endl;
    cout << "maxShiftSteps = " << m_cfg.maxShiftSteps << endl;
    cout << "readChanWidth = " << m_cfg.readChanWidth << endl;
    cout << "rhsEntriesPerMem = " << m_cfg.rhsEntriesPerMem << endl;
    cout << "writeChanWidth = " << m_cfg.writeChanWidth << endl;
  }

protected:
  BitSerialMatMulAccel *m_accel;
  WrapperRegDriver *m_platform;
  HardwareCfg m_cfg;
  float m_fclk;

  // get the instantiated hardware config from accelerator
  void update_hw_cfg()
  {
    m_cfg.accWidth = m_accel->get_hw_accWidth();
    m_cfg.cmdQueueEntries = m_accel->get_hw_cmdQueueEntries();
    m_cfg.dpaDimCommon = m_accel->get_hw_dpaDimCommon();
    m_cfg.dpaDimLHS = m_accel->get_hw_dpaDimLHS();
    m_cfg.dpaDimRHS = m_accel->get_hw_dpaDimRHS();
    m_cfg.lhsEntriesPerMem = m_accel->get_hw_lhsEntriesPerMem();
    m_cfg.maxShiftSteps = m_accel->get_hw_maxShiftSteps();
    m_cfg.readChanWidth = m_accel->get_hw_readChanWidth();
    m_cfg.rhsEntriesPerMem = m_accel->get_hw_rhsEntriesPerMem();
    m_cfg.writeChanWidth = m_accel->get_hw_writeChanWidth();
  }
};
#endif // BitSerialMatMulAccelDriver_H