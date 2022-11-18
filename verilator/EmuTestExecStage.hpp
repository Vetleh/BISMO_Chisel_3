
#ifndef EmuTestExecStage_H
#define EmuTestExecStage_H
#include "wrapperregdriver.h"
#include <map>
#include <string>
#include <vector>

// template parameters used for instantiating TemplatedHLSBlackBoxes, if any:


using namespace std;
class EmuTestExecStage {
public:
  EmuTestExecStage(WrapperRegDriver * platform) {
    m_platform = platform;
    attach();
    if(readReg(0) != 0xf001443a)  {
      throw "Unexpected accelerator signature, is the correct bitfile loaded?";
    }
  }
  ~EmuTestExecStage() {
    detach();
  }

    AccelReg get_resmem_data() {return readReg(1);} 
  void set_resmem_addr_e(AccelReg value) {writeReg(2, value);} 
  void set_resmem_addr_c(AccelReg value) {writeReg(3, value);} 
  void set_resmem_addr_r(AccelReg value) {writeReg(4, value);} 
  void set_tilemem_rhs_write(AccelReg value) {writeReg(5, value);} 
  void set_tilemem_rhs_data(AccelDblReg value) { writeReg(6, (AccelReg)(value >> 32)); writeReg(7, (AccelReg)(value & 0xffffffff)); }
  void set_tilemem_rhs_addr(AccelReg value) {writeReg(8, value);} 
  void set_tilemem_rhs_sel(AccelReg value) {writeReg(9, value);} 
  void set_tilemem_lhs_write(AccelReg value) {writeReg(10, value);} 
  void set_tilemem_lhs_data(AccelDblReg value) { writeReg(11, (AccelReg)(value >> 32)); writeReg(12, (AccelReg)(value & 0xffffffff)); }
  void set_tilemem_lhs_addr(AccelReg value) {writeReg(13, value);} 
  void set_tilemem_lhs_sel(AccelReg value) {writeReg(14, value);} 
  void set_csr_writeAddr(AccelReg value) {writeReg(15, value);} 
  void set_csr_writeEn(AccelReg value) {writeReg(16, value);} 
  void set_csr_clear_before_first_accumulation(AccelReg value) {writeReg(17, value);} 
  void set_csr_negate(AccelReg value) {writeReg(18, value);} 
  void set_csr_shiftAmount(AccelReg value) {writeReg(19, value);} 
  void set_csr_numTiles(AccelReg value) {writeReg(20, value);} 
  void set_csr_rhsOffset(AccelReg value) {writeReg(21, value);} 
  void set_csr_lhsOffset(AccelReg value) {writeReg(22, value);} 
  AccelReg get_cfg_config_rhs_mem() {return readReg(23);} 
  AccelReg get_cfg_config_lhs_mem() {return readReg(24);} 
  AccelReg get_cfg_config_dpu_z() {return readReg(25);} 
  AccelReg get_cfg_config_dpu_y() {return readReg(26);} 
  AccelReg get_cfg_config_dpu_x() {return readReg(27);} 
  AccelReg get_done() {return readReg(28);} 
  void set_start(AccelReg value) {writeReg(29, value);} 
  AccelReg get_signature() {return readReg(0);} 


  map<string, vector<unsigned int>> getStatusRegs() {
    map<string, vector<unsigned int>> ret = { {"resmem_data", {1}} ,  {"cfg_config_rhs_mem", {23}} ,  {"cfg_config_lhs_mem", {24}} ,  {"cfg_config_dpu_z", {25}} ,  {"cfg_config_dpu_y", {26}} ,  {"cfg_config_dpu_x", {27}} ,  {"done", {28}} ,  {"signature", {0}} };
    return ret;
  }

  AccelReg readStatusReg(string regName) {
    map<string, vector<unsigned int>> statRegMap = getStatusRegs();
    if(statRegMap[regName].size() != 1) throw ">32 bit status regs are not yet supported from readStatusReg";
    return readReg(statRegMap[regName][0]);
  }

protected:
  WrapperRegDriver * m_platform;
  AccelReg readReg(unsigned int i) {return m_platform->readReg(i);}
  void writeReg(unsigned int i, AccelReg v) {m_platform->writeReg(i,v);}
  void attach() {m_platform->attach("EmuTestExecStage");}
  void detach() {m_platform->detach();}
};
#endif
    