.DEFAULT_GOAL := spike
SIM_PATH = ../../../../../../sims/verilator/simulator-chipyard-BISMORoCCRocketConfig
SIM_DEBUG_PATH = ../../../../../../sims/verilator/simulator-chipyard-BISMORoCCRocketConfig-debug
BUILD_TOOLS_PATH = ../../../../../../scripts/build-toolchain-extra.sh
PATH_FROM_SIM = ../../generators/BISMO_Chisel_3/src/main/cpp/app
sim_compile:
	$(BUILD_TOOLS_PATH)
	riscv64-unknown-linux-gnu-g++ -static cpptest.cpp -o cpptest
	$(SIM_PATH) pk ./cpptest

spike-debug-2:
	riscv64-unknown-linux-gnu-g++ -static cpptest.cpp -o cpptest
	spike pk cpptest

spike:
	riscv64-unknown-linux-gnu-g++ -falign-functions=256 -falign-loops=256 -falign-jumps=256 -static main.cpp -o main

spike-test:
	riscv64-unknown-linux-gnu-g++ -static test.cpp -o test
	spike pk test

spike-debug:
	riscv64-unknown-linux-gnu-g++ -static main.cpp -o main
	spike -d pk main

sim:
	riscv64-unknown-linux-gnu-g++ -static main.cpp -o main
	test -d out || mkdir out
	time $(SIM_PATH) --cycle-count pk ./main 2>&1 | tee out/bismo-log.txt

sim-debug:
	riscv64-unknown-linux-gnu-g++ -static main.cpp -o main
	test -d out || mkdir out
	time $(SIM_DEBUG_PATH) --cycle-count --vcd=out/bismo-debug-clock.vcd pk ./main 2>&1 | tee out/bismo-log-clock.txt