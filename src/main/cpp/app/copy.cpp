// ResultRunCfg rrc = {
//         .dram_base = (void *)res_base,
//         .dram_skip = lhs_eff_rows() * sizeof(ResultType),
//         .lhs_l1_per_l2 = lhs_l1_per_l2,
//         .rhs_l1_per_l2 = rhs_l1_per_l2,
//         .lhs_l2_per_matrix = lhs_l2_per_matrix,
//         .rhs_l2_per_matrix = rhs_l2_per_matrix,
//         .z_l2_per_matrix = z_l2_per_matrix,
//         .nrows_a = lhs_eff_rows()};
//     makeinstr_result_run(rrc);