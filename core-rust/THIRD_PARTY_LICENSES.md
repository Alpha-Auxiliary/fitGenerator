# 直接依赖许可证清单

本文件仅按 [`Cargo.toml`](Cargo.toml) 记录 `fit-generator-core` 的直接依赖，不包含 Cargo 解析出的传递依赖，也不是法律意见或完整的发布归档。

当前仓库没有提交由 `cargo-deny`/`cargo-about` 生成并可据此独立确认的许可证报告。为避免根据记忆猜测许可证，下面所有许可证字段均明确标记为“待 CI 验证”。发布流水线必须根据实际解析到的精确版本、registry 元数据和许可证文本完成核对，再生成包含全部传递依赖的最终 notices。

## 运行时依赖

| 依赖 | Cargo 版本要求 | 启用方式/feature | 许可证状态 |
| --- | --- | --- | --- |
| `libm` | `0.2` | 默认运行时依赖 | 待 CI 使用 `cargo-deny` / `cargo-about` 验证；本文不作许可证断言 |
| `serde` | `1` | features: `derive` | 待 CI 使用 `cargo-deny` / `cargo-about` 验证；本文不作许可证断言 |
| `serde_json` | `1` | 默认运行时依赖 | 待 CI 使用 `cargo-deny` / `cargo-about` 验证；本文不作许可证断言 |
| `thiserror` | `2` | 默认运行时依赖 | 待 CI 使用 `cargo-deny` / `cargo-about` 验证；本文不作许可证断言 |
| `time` | `0.3` | features: `formatting`, `parsing`, `serde` | 待 CI 使用 `cargo-deny` / `cargo-about` 验证；本文不作许可证断言 |
| `jni` | `0.21` | optional；由 feature `android-jni` 启用 | 待 CI 使用 `cargo-deny` / `cargo-about` 验证；本文不作许可证断言 |

## 开发与测试依赖

| 依赖 | Cargo 版本要求 | 用途 | 许可证状态 |
| --- | --- | --- | --- |
| `fitparser` | `0.10` | 独立解码生成的 FIT 测试数据 | 待 CI 使用 `cargo-deny` / `cargo-about` 验证；本文不作许可证断言 |
| `proptest` | `1` | 属性测试与任意输入边界测试 | 待 CI 使用 `cargo-deny` / `cargo-about` 验证；本文不作许可证断言 |

## 发布前许可证门禁

CI 至少应在锁定依赖后执行：

```bash
cargo deny --manifest-path core-rust/Cargo.toml --all-features --locked check bans licenses sources
cargo deny --manifest-path core-rust/Cargo.toml --all-features check advisories
git diff --exit-code -- core-rust/Cargo.lock
```

第一条在已审查的锁文件上核对许可证、禁用项和来源；第二条允许 `cargo-deny` 更新安全公告数据库，紧随的检查保证该过程没有改写 `Cargo.lock`。

随后应使用 `cargo-about` 和仓库审核过的配置/模板生成完整 notices；在配置和模板尚未提交前，本文件不提供可能误导的伪生成命令。发布审核需要确认：

1. 直接与传递依赖的精确版本及来源均已锁定；
2. registry/Git 来源符合项目策略；
3. 每项依赖的 SPDX 表达式和实际许可证文本一致；
4. 必需的版权声明、许可证全文和 notices 已随发行物提供；
5. `Cargo.toml`、锁文件、生成报告与本清单之间没有遗漏。

crate 自身在 `Cargo.toml` 中声明为 `MIT`；项目许可证正文见仓库根目录 [`LICENSE`](../LICENSE)。这项声明不替代第三方依赖的独立许可证义务。
