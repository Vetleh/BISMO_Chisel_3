
#ifndef EmuTestFetchStage_H
#define EmuTestFetchStage_H
#include "wrapperregdriver.h"
#include <map>
#include <string>
#include <vector>

// template parameters used for instantiating TemplatedHLSBlackBoxes, if any:


using namespace std;
class EmuTestFetchStage {
public:
  EmuTestFetchStage(WrapperRegDriver * platform) {
    m_platform = platform;
    attach();
    if(readReg(0) != 0x63af30db)  {
      throw "Unexpected accelerator signature, is the correct bitfile loaded?";
    }
  }
  ~EmuTestFetchStage() {
    detach();
  }

    AccelDblReg get_bram_rsp() { return (AccelDblReg)readReg(2) << 32 | (AccelDblReg)readReg(1); }
  void set_bram_req_writeEn(AccelReg value) {writeReg(3, value);} 
  void set_bram_req_writeData(AccelDblReg value) { writeReg(4, (AccelReg)(value >> 32)); writeReg(5, (AccelReg)(value & 0xffffffff)); }
  void set_bram_req_addr(AccelReg value) {writeReg(6, value);} 
  void set_bram_sel(AccelReg value) {writeReg(7, value);} 
  void set_csr_bram_id_range(AccelReg value) {writeReg(8, value);} 
  void set_csr_bram_id_start(AccelReg value) {writeReg(9, value);} 
  void set_csr_bram_addr_base(AccelReg value) {writeReg(10, value);} 
  void set_csr_tiles_per_row(AccelReg value) {writeReg(11, value);} 
  void set_csr_dram_block_count(AccelReg value) {writeReg(12, value);} 
  void set_csr_dram_block_offset_bytes(AccelReg value) {writeReg(13, value);} 
  void set_csr_dram_block_size_bytes(AccelReg value) {writeReg(14, value);} 
  void set_csr_dram_base(AccelDblReg value) { writeReg(15, (AccelReg)(value >> 32)); writeReg(16, (AccelReg)(value & 0xffffffff)); }
  AccelReg get_perf_cycles() {return readReg(17);} 
  AccelReg get_done() {return readReg(18);} 
  void set_start(AccelReg value) {writeReg(19, value);} 
  AccelReg get_signature() {return readReg(0);} 


  map<string, vector<unsigned int>> getStatusRegs() {
    map<string, vector<unsigned int>> ret = { {"bram_rsp", {1, 2}} ,  {"perf_cycles", {17}} ,  {"done", {18}} ,  {"signature", {0}} };
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
  void attach() {m_platform->attach("EmuTestFetchStage");}
  void detach() {m_platform->detach();}
};
#endif
    