# Compose、Room 和 kotlinx-serialization 都会随依赖提供 R8 consumer rules。
# 仅当 R8 full mode 报出缺失规则时，再把规则收敛到具体类，避免全局 keep 阻碍裁剪与优化。
