
#ifndef EmuTestResultStage_H
#define EmuTestResultStage_H
#include "wrapperregdriver.h"
#include <map>
#include <string>
#include <vector>

// template parameters used for instantiating TemplatedHLSBlackBoxes, if any:


using namespace std;
class EmuTestResultStage {
public:
  EmuTestResultStage(WrapperRegDriver * platform) {
    m_platform = platform;
    attach();
    if(readReg(0) != 0xf259670e)  {
      throw "Unexpected accelerator signature, is the correct bitfile loaded?";
    }
  }
  ~EmuTestResultStage() {
    detach();
  }

    void set_accwr_data(AccelReg value) {writeReg(1, value);} 
  void set_accwr_rhs(AccelReg value) {writeReg(2, value);} 
  void set_accwr_lhs(AccelReg value) {writeReg(3, value);} 
  void set_accwr_en(AccelReg value) {writeReg(4, value);} 
  void set_csr_resmem_addr(AccelReg value) {writeReg(5, value);} 
  void set_csr_waitCompleteBytes(AccelReg value) {writeReg(6, value);} 
  void set_csr_waitComplete(AccelReg value) {writeReg(7, value);} 
  void set_csr_dram_skip(AccelDblReg value) { writeReg(8, (AccelReg)(value >> 32)); writeReg(9, (AccelReg)(value & 0xffffffff)); }
  void set_csr_dram_base(AccelDblReg value) { writeReg(10, (AccelReg)(value >> 32)); writeReg(11, (AccelReg)(value & 0xffffffff)); }
  AccelReg get_done() {return readReg(12);} 
  void set_start(AccelReg value) {writeReg(13, value);} 
  AccelReg get_signature() {return readReg(0);} 


  map<string, vector<unsigned int>> getStatusRegs() {
    map<string, vector<unsigned int>> ret = { {"done", {12}} ,  {"signature", {0}} };
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
  void attach() {m_platform->attach("EmuTestResultStage");}
  void detach() {m_platform->detach();}
};
#endif
    