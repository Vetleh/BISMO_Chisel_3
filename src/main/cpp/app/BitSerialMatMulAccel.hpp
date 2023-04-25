#ifndef BitSerialMatMulAccel_H
#define BitSerialMatMulAccel_H
#include <map>
#include <string>
#include <vector>
#include "rocc.h"
#include <stdio.h>

// template parameters used for instantiating TemplatedHLSBlackBoxes, if any:
typedef unsigned int AccelReg;
typedef uint64_t AccelDblReg;
// TODO create all the addresses in a seperate header file for easier
// debugging and making it easier to change later
using namespace std;
class BitSerialMatMulAccel
{
public:
  BitSerialMatMulAccel() {}
  // Result perf
  void reset_accel(AccelReg value) { bismo_rocc_write(0, value); }
  AccelReg get_signature()  { return bismo_rocc_read(1); }
  void set_perf_prf_res_sel(AccelReg value) { bismo_rocc_write(25, value); }
  AccelReg get_perf_prf_res_count() { return bismo_rocc_read(24); }
  void set_perf_prf_exec_sel(AccelReg value) { bismo_rocc_write(23, value); }
  AccelReg get_perf_prf_exec_count() { return bismo_rocc_read(22); }
  void set_perf_prf_fetch_sel(AccelReg value) { bismo_rocc_write(21, value); }
  AccelReg get_perf_prf_fetch_count() { bismo_rocc_read(20); }
  void set_perf_cc_enable(AccelReg value) { bismo_rocc_write(19, value); }
  AccelReg get_perf_cc() { return bismo_rocc_read(18); }
  AccelReg get_hw_cmdQueueEntries() { return bismo_rocc_read(17); }
  AccelReg get_hw_maxShiftSteps() { return bismo_rocc_read(16); }
  AccelReg get_hw_accWidth() { return bismo_rocc_read(15); }
  AccelReg get_hw_rhsEntriesPerMem() { return bismo_rocc_read(14); }
  AccelReg get_hw_lhsEntriesPerMem() { return bismo_rocc_read(13); }
  AccelReg get_hw_dpaDimCommon() { return bismo_rocc_read(12); }
  AccelReg get_hw_dpaDimRHS() { return bismo_rocc_read(11); }
  AccelReg get_hw_dpaDimLHS() { return bismo_rocc_read(10); }
  AccelReg get_hw_writeChanWidth() { return bismo_rocc_read(9); }
  AccelReg get_hw_readChanWidth() { return bismo_rocc_read(8); }
  AccelReg get_result_op_count() { return bismo_rocc_read(7); }
  AccelReg get_exec_op_count() { return bismo_rocc_read(6); }
  AccelReg get_fetch_op_count() { return bismo_rocc_read(5);}
  void set_ins_bits_dpa_z_bytes(AccelDblReg value) { bismo_rocc_write(50, value); }
  void set_ins_bits_nrows_a(AccelReg value) { bismo_rocc_write(49, value); }
  void set_ins_bits_rhs_bytes_per_l2(AccelDblReg value) { bismo_rocc_write(48, value); }
  void set_ins_bits_lhs_bytes_per_l2(AccelDblReg value) { bismo_rocc_write(47, value); }
  void set_ins_bits_z_l2_per_matrix(AccelReg value) { bismo_rocc_write(46, value); }
  void set_ins_bits_rhs_l2_per_matrix(AccelReg value) { bismo_rocc_write(45, value); }
  void set_ins_bits_lhs_l2_per_matrix(AccelReg value) { bismo_rocc_write(44, value);}
  void set_ins_bits_rhs_l1_per_l2(AccelReg value) { bismo_rocc_write(43, value); }
  void set_ins_bits_lhs_l1_per_l2(AccelReg value) { bismo_rocc_write(42, value);}
  void set_ins_bits_rhs_l0_per_l1(AccelDblReg value) { bismo_rocc_write(41, value); }
  void set_ins_bits_lhs_l0_per_l1(AccelDblReg value) { bismo_rocc_write(40, value); }
  void set_ins_bits_wait_complete_bytes(AccelDblReg value) { bismo_rocc_write(39, value); }
  void set_ins_bits_dram_skip(AccelDblReg value) { bismo_rocc_write(38, value); }
  void set_ins_bits_dram_base(AccelDblReg value) { bismo_rocc_write(37, value); }
  void set_ins_bits_negate(AccelReg value) { bismo_rocc_write(36, value); }
  void set_ins_bits_shiftAmount(AccelReg value) { bismo_rocc_write(35, value); }
  void set_ins_bits_numTiles(AccelReg value) { bismo_rocc_write(34, value); }
  void set_ins_bits_tiles_per_row(AccelReg value) { bismo_rocc_write(33, value);}
  void set_ins_bits_dram_block_count(AccelReg value) { bismo_rocc_write(32, value); }
  void set_ins_bits_dram_block_size_bytes(AccelReg value) { bismo_rocc_write(31, value); }
  void set_ins_bits_dram_block_offset_bytes(AccelReg value) { bismo_rocc_write(30, value); }
  void set_ins_bits_dram_base_rhs(AccelDblReg value) { bismo_rocc_write(29, value); }
  void set_ins_bits_dram_base_lhs(AccelDblReg value) { bismo_rocc_write(28, value); }
  void set_ins_valid(AccelReg value) { bismo_rocc_write(27, value); }
  AccelReg get_ins_ready() { return bismo_rocc_read(26); }
  void set_result_enable(AccelReg value) { bismo_rocc_write(3, value); }
  void set_exec_enable(AccelReg value) { bismo_rocc_write(2, value); }
  void set_fetch_enable(AccelReg value) { bismo_rocc_write(1, value); }

  static inline void bismo_rocc_write(int idx, unsigned long data)
  {
    ROCC_INSTRUCTION_SS(0, data, idx, 0);
  }

  static inline unsigned long bismo_rocc_read(int idx)
  {
    unsigned long value;
    ROCC_INSTRUCTION_DSS(0, value, 0, idx, 1);
    return value;
  }
};
#endif