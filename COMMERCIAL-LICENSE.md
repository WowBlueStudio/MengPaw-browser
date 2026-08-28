# MengPaw Browser 双许可声明

> 本仓库是 MengPaw（檬爪）按 APK 产物拆分出的**浏览器独立仓库**。
> 完整双许可条款见主仓库 `WowBlueStudio/MengPaw` 的 `COMMERCIAL-LICENSE.md`。

MengPaw Browser 以**双许可**方式发布，使用者可在下列两种许可中选择其一：

| | 社区版 | 商业版 |
|---|--------|--------|
| 许可 | GNU AGPL v3.0（见 [LICENSE](LICENSE)） | 商业授权（条款见主仓库 COMMERCIAL-LICENSE.md 第二节） |
| 费用 | 免费 | 付费（费用双方协商） |
| 源码 | 公开（GitHub / Gitee） | 公开（GitHub / Gitee） |

## SPDX 头

本仓库所有 `.kt` / `.kts` 文件须携带双许可版权头：

```
// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial
```

## 共享地基许可边界

共享地基（kernel/core/design-system）为 AGPL，编译期经 JitPack 依赖、不作为源码分发——本仓库对共享地基的**编译期引用不构成衍生作品**，仅运行期组合适用浏览器自身的双许可。
