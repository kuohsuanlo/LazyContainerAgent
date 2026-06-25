# template/ — v1.0 架構參考

此目錄包含 v1.0 的 `LazyContainerTemplate.java`，是 v1.0 架構的核心：

- v1.0 透過「編譯對 real NMS」的 template class，將其 bytes splice 進 `BaseContainerBlockEntity`
- v2 不再使用此方式。改以 reflection + context classloader 直接存取 NMS 型別
- 此檔案保留作為架構對照參考，**不參與建置**

若要編譯（僅供研究）:
```bash
bash build.sh --with-template   # 需 nms-lib/ 目錄
```
