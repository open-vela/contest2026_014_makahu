# S1 私有存储配置与失败路径

日期：2026-10-24。任务 05 的存储子阶段；仅代码实现和静态回顾，没有配置生成、测试、构建、打包、格式化、烧录或设备操作。完整源事件网络复制仍未完成。

## 专用配置与布局

新增 SDK 板配置 `vendor/allwinnertech/boards/r528/r528s3-gemini-s1/configs/buffer-private/defconfig`，基于现行 `nsh_minidisplay`，保留 ILI9341、PD20/PWM4、多级亮度与夜间调光。开启 Buffer、Dhara、LittleFS 文件属性和源 journal；Buffer 数据目录为 `/private/buffer`，Wi-Fi 配置为 `/private/wapi.conf`。原 `nsh`、`nsh_minidisplay` 和普通 `sys_partition.fex` 未因本阶段改动。

独立分区表 `sys_partition_buffer_private.fex` 在 `res` 与 `usrdata` 之间增加 `bufpriv`。物理区相对起点为 82432 个 512 字节扇区，大小 131072 扇区（64 MiB）；`usrdata` 缩为 310784 扇区，总布局大小不变。私有分区放在 `usrdata` 前，使其处于 SDK 现有 sst 至 usrdata 末尾的允许写入范围。Dhara 的逻辑可用容量小于原始 64 MiB，保留映射、坏块和回收空间。

**这是新的工厂布局，不能作为旧盘的原地升级。** `usrdata` 起点改变；既有数据必须独立备份和迁移。本阶段没有执行或实现自动迁移，不会把旧 FAT 文件复制进私有卷。

SDK 打包脚本增加显式 `-m buffer-private-factory` 模式，仅允许项目 `r528s3/gemini-s1_nand`，将独立表复制到打包暂存区的 `sys_partition.fex`，输出名加入 `buffer-private-factory`。普通模式继续使用原表。`envsetup.sh` 的 `pack -m` 原本表示 dump，因此新模式应传给 `tools/scripts/pack_img.sh`，沿用该项目其余打包参数；不能将 `pack -m buffer-private-factory` 当作现有封装函数的调用方式。板配置也指向独立表，但这个 Kconfig 路径本身不会替代打包脚本的暂存表选择。

`bufpriv` 不带 downloadfile，不写入 FAT、裸 LittleFS 或其他文件系统镜像。工厂流程须保证该区域已擦除；代码会再次核对，不假设烧录工具已经完成擦除。

## 普通启动

`buffer_store_init` 和 Agent 私有后端均先调用 `buffer_private_storage_prepare`。后者只接受固定 `/dev/bufpriv`：校验原始 NAND 的读写/擦除/坏块方法、页和擦除几何、64 MiB 容量，以及 BIOC_PARTINFO 的 parent、精确起点与擦除块对齐。

后端以严格恢复模式注册 `/dev/buffer-private`，保留底层 MTD inode 引用，避免 Dhara 借用的指针失效；以无格式化选项的 LittleFS 挂载到 `/private`。已有同名挂载点不会被遮盖；后续调用核对本后端的挂载状态和文件系统类型。失败原样返回，应用启动报告存储初始化失败，不继续写旧目录、创建明文凭据或 ACK 清理原音频。

源 journal 在普通存储创建队列前初始化。Agent 仍检查 LittleFS 类型与真实文件属性；挂载成功不是权限验证的替代品。Fabric 身份和绑定沿用 Buffer 数据目录，因此使用私有卷；这不表示本配置已完成 Fabric 传输或多成员接入。

## 显式初始化

本地 CLI 提供 `buffer_app agent storage-provision --initialize-erased-bufpriv`。应用已启动或本进程已注册该映射时拒绝。它不接受任意设备路径，不执行原始擦除；核对固定分区几何后逐块检查坏块，对所有好块逐页读取，只有全部主数据字节为 FF 才允许首次 Dhara 初始化和 LittleFS `forceformat`。

非空卷、不可纠正读取错误、缺失分区、错误别名/布局和无可用好块均拒绝。可纠正 ECC 返回完整页，可继续检查；旧卷损坏不会被当作可格式化空卷。初始化中途失败明确返回错误，不能据此自动重置卷或继续启动。恢复非空故障卷需要独立恢复流程；不增加破坏性重置入口。

## SDK 必要修正

- Dhara 增加带严格恢复选项的注册接口；原接口保持既有兼容语义，新后端使用严格接口。检查缓存分配、页/块几何、容量和读写范围，拒绝零容量映射。
- Dhara 对 LittleFS 报告逻辑页几何，而非原始擦除块几何；LittleFS read/prog/cache/block 倍率全部为 1，应用编译条件明确要求这些值。
- `BIOC_FLUSH` 先锁定并同步 Dhara 映射，再提交底层设备；不能仅 flush 原 NAND 而遗漏逻辑映射。底层明确不支持额外 flush 时保留同步 NAND 契约。
- Dhara 页读写要求完整一页，正确处理可纠正 ECC；LittleFS VFS 拒绝短读和短写，防止把部分成功当作持久成功。
- S1 NAND 的擦除与缓存提交失败返回错误；页读写拒绝越界检查失败，非可纠正读取错误不再计为成功；物理分区注册失败向上返回，不再打印初始化成功。已经被驱动引用的后端不因部分注册失败而释放。

变更保存为 `patches/openvela/0013-dhara-durable-private-map.patch`（NuttX 根）与 `0014-gemini-s1-private-storage-profile.patch`（vendor/allwinnertech 根）。补丁以本阶段开始前的文件为基线，包含已有 LittleFS 属性补丁之后的增量，不混入先前 SDK 改动。未执行补丁应用检查。

## 静态回顾与边界

已读取并对照板配置、独立分区表、打包暂存选择、NAND 物理分区注册、MTD partition 的几何与起点换算、Dhara 恢复/读写/flush、LittleFS 挂载/格式化条件和 VFS 短 I/O 处理，以及 Buffer/Agent/Wi-Fi 的存储调用顺序。NuttX 在文件系统 bind 成功后才创建新挂载点，失败挂载不会因本后端预建目录而阻断重试。

Make/CMake 已包含私有存储模块；现有 host fixture 仅更新链接源文件列表，没有运行。此阶段勾选仅证明配置和调用路径在源码中接通；坏块寿命、掉电恢复、容量、挂载行为和真实权限仍待构建及硬件验收。任务 05 的源事件协议传输、父项/权限序号处理、投影重放与 blob 交付继续保持未完成。
