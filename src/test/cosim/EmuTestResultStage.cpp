#include <cassert>
#include <iostream>
#include <iomanip>
using namespace std;
#include "platform.h"
#include "EmuTestResultStage.hpp"

// Cosim test for basic ResultStage behavior

#define DPA_LHS 2
#define DPA_RHS 2
typedef int32_t ResultType;

WrapperRegDriver *p;
EmuTestResultStage *dut;

void exec_and_wait()
{
  dut->set_start(1);
  while (dut->get_done() != 1)
    ;
  dut->set_start(0);
}

// get offset of element (x, y) in a 1D array[nx][ny]
int offset2D(int x, int y, int ny)
{
  return x * ny + y;
}

void do_result_write(void *accel_buf, int ind_rhs, int ind_lhs, int num_rhs, int num_lhs)
{
  size_t stride = num_lhs * sizeof(ResultType);
  uint64_t base = sizeof(int32_t) * offset2D(ind_rhs, ind_lhs, num_lhs) + (uint64_t)accel_buf;

  dut->set_csr_dram_base(base);
  dut->set_csr_dram_skip(stride);
  dut->set_csr_waitComplete(0);
  dut->set_csr_waitCompleteBytes(0);
  dut->set_csr_resmem_addr(0);
  exec_and_wait();
}

void do_result_waitcomplete(size_t nbytes)
{
  dut->set_csr_waitComplete(1);
  dut->set_csr_waitCompleteBytes(nbytes);
  exec_and_wait();
}

void write_resmem(int lhs, int rhs, int val)
{
  dut->set_accwr_lhs(lhs);
  dut->set_accwr_rhs(rhs);
  dut->set_accwr_data(val);
  dut->set_accwr_en(1);
  dut->set_accwr_en(0);
}

int main()
{
  bool all_OK = true;
  p = initPlatform();
  dut = new EmuTestResultStage(p);

  bool simple_res_OK = true;
  const size_t nrhs = DPA_RHS * 1, nlhs = DPA_LHS * 2;
  size_t nbytes = sizeof(ResultType) * nrhs * nlhs;
  uint32_t *hostbuf = new uint32_t[nrhs * nlhs];
  void *accelbuf = p->allocAccelBuffer(nbytes);

  // each tile is written to memory in column-major order, with appropriate
  // stride to account for the tiling. e.g the 2x2 DPA array:
  //   (<---rhs dim--->)       ^
  //          A B            lhs dim
  //          C D              v
  // turns into:
  // A, C, [stride], B, D
  // when written into memory, so the lhs entires remain contiguous, "rhs-major"

  // Sets the array:
  //  ___________
  // |  1  |  5  |
  // |  2  |  6  |
  // |  3  |  7  |
  // |  4  |  8  |
  //  ‾‾‾‾‾‾‾‾‾‾‾

  // dut->set_acc_in_0_0(1);
  write_resmem(0, 0, 1);
  // dut->set_acc_in_1_0(2);
  write_resmem(1, 0, 2);
  // dut->set_acc_in_0_1(5);
  write_resmem(0, 1, 5);
  // dut->set_acc_in_1_1(6);
  write_resmem(1, 1, 6);
  do_result_write(accelbuf, 0 * DPA_RHS, 0 * DPA_LHS, nrhs, nlhs);

  // dut->set_acc_in_0_0(3);
  write_resmem(0, 0, 3);
  // dut->set_acc_in_1_0(4);
  write_resmem(1, 0, 4);
  // dut->set_acc_in_0_1(7);
  write_resmem(0, 1, 7);
  // dut->set_acc_in_1_1(8);
  write_resmem(1, 1, 8);
  do_result_write(accelbuf, 0 * DPA_RHS, 1 * DPA_LHS, nrhs, nlhs);

  // wait until all writes are completed
  do_result_waitcomplete(nbytes);

  p->copyBufferAccelToHost(accelbuf, hostbuf, nbytes);

  for (int i = 0; i < nrhs * nlhs; i++)
  {
    simple_res_OK &= (hostbuf[i] == i + 1);
  }

  all_OK &= simple_res_OK;
  // Print table with current output and expected output
  if (!simple_res_OK)
  {
    cout << left << setw(37) << setfill('-') << "" << endl;
    cout << left << setw(16) << setfill(' ') << "Your result:";
    cout << left << setw(5) << setfill(' ') << "|";
    cout << left << setw(17) << setfill(' ') << "Expected result:" << endl;
    cout << left << setw(37) << setfill('-') << "" << endl;

    for (int i = 0; i < nrhs * nlhs; i++)
    {
      cout << "i[" << i << "] = " << left << setw(9) << setfill(' ') << hostbuf[i];
      cout << left << setw(5) << setfill(' ') << "|";
      cout << left << setw(0) << setfill(' ') << ""
           << "i[" << i << "] = " << i + 1 << endl;
    }
    cout << left << setw(37) << setfill('-') << "" << endl;
  }

  if (all_OK)
  {
    cout << "Test passed" << endl;
  }
  else
  {
    cout << "Test failed" << endl;
  }

  delete[] hostbuf;

  delete dut;
  deinitPlatform(p);
  return all_OK ? 0 : -1;
}