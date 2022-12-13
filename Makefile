# target frequency for Vivado FPGA synthesis
FREQ_MHZ ?= 150.0
# controls whether Vivado will run in command-line or GUI mode
VIVADO_MODE ?= batch # or gui
# which C++ compiler to use
CC = g++
# scp/rsync target to copy files to board
PLATFORM ?= VerilatedTester
URI = $($(PLATFORM)_URI)
# overlay dims
M ?= 8
K ?= 256
N ?= 8
OVERLAY_CFG = $(M)x$(K)x$(N)

TOP ?= $(shell dirname $(realpath $(filter %Makefile, $(MAKEFILE_LIST))))
# other project settings
SBT ?= sbt 
SBT_FLAGS ?= -Dsbt.log.noformat=true 

BUILD_PATH ?= build

BUILD_DIR ?= build

# internal build dirs and names for the Makefile
BUILD_DIR_VERILOG := $(BUILD_PATH)/hw/verilog 

HW_VERILOG := $(BUILD_DIR_VERILOG)/$(PLATFORM)Wrapper.v 

CPPTEST_SRC_DIR := $(TOP)/src/test/cosim


# BISMO is run in emulation mode by default if no target is provided
.DEFAULT_GOAL := emu

# note that all targets are phony targets, no proper dependency tracking
.PHONY: hw_verilog emulib hw_driver hw_vivadoproj bitfile hw sw all rsync test characterize check_vivado emu emu_cfg

# generate Verilog for the Chisel accelerator
hw_verilog: $(HW_VERILOG)

$(HW_VERILOG):
	$(SBT) $(SBT_FLAGS) "runMain bismo.ChiselMain $(PLATFORM) $(BUILD_DIR_VERILOG) $M $K $N"

# generate register driver for the Chisel accelerator
hw_driver: $(BUILD_DIR_HWDRV)/BitSerialMatMulAccel.hpp

$(BUILD_DIR_HWDRV)/BitSerialMatMulAccel.hpp:
	mkdir -p "$(BUILD_DIR_HWDRV)"
	$(SBT) $(SBT_FLAGS) "runMain bismo.DriverMain $(PLATFORM) $(BUILD_DIR_HWDRV) $(TIDBITS_REGDRV_ROOT)"

EmuTest%:
	mkdir -p $(BUILD_DIR)/$@
	$(SBT) $(SBT_FLAGS) "runMain bismo.EmuLibMain $@ $(BUILD_DIR)/$@"
	cp -r $(CPPTEST_SRC_DIR)/$@.cpp $(BUILD_DIR)/$@
	cd $(BUILD_DIR)/$@; ./verilator-build.sh; ./VerilatedTesterWrapper

emu: $(TOP)/build/smallEmu/driver.a
	cp -r $(APP_SRC_DIR)/* $(TOP)/build/smallEmu/
	cd $(TOP)/build/smallEmu; g++ -std=c++11 *.cpp driver.a -o emu; ./emu

# remove everything that is built
clean:
	rm -rf $(BUILD_DIR)

# remove everything that is built
clean_all:
	rm -rf $(TOP)/build