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

# other project settings
SBT ?= sbt 
SBT_FLAGS ?= -Dsbt.log.noformat=true 
# internal build dirs and names for the Makefile
BUILD_DIR_VERILOG := $(BUILD_DIR)/hw/verilog 

HW_VERILOG := $(BUILD_DIR_VERILOG)/$(PLATFORM)Wrapper.v 


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


# remove everything that is built
clean:
	rm -rf $(BUILD_DIR)

# remove everything that is built
clean_all:
	rm -rf $(TOP)/build