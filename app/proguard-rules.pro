# Memo R8 rules
# Keep rules are added per-feature as they land.

# PDFBox 的 JPXFilter 引用了未打包的 gemalto JPEG2000 解码器，R8 因为这条悬空引用
# 直接判定编译失败（minifyReleaseWithR8）。文档抽取不走 JPX 编码的 PDF，忽略即可。
-dontwarn com.gemalto.jp2.**
