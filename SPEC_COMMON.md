# Finance プラグイン 仕様 — 共通層（STEP L0〜）

Apache OFBiz 上に構築する独立ファイナンスモジュール（`plugins/finance`）のうち、
ローン業務とリース業務が共有する **共通層** の詳細仕様・運用記録をまとめる。

| 文書 | 対象 |
|------|------|
| `REQUIREMENTS.md` | ローン業務の要件（STEP 1〜10） |
| `REQUIREMENTS_LEASE.md` | 共通層の切り出し（STEP L0）とリース業務の要件（STEP L1〜） |
| **`SPEC_COMMON.md`（本書）** | 共通層の詳細仕様・運用記録 |
| `SPEC.md` | ローン層（STEP 1〜10）の詳細仕様・運用記録 |
| `SPEC_LEASE.md` | リース層（STEP L1〜）の詳細仕様・運用記録 |

記録の振り分け：

- 共通層（`FinanceContract`、契約ロック、審査、与信合算、延滞・督促、利息計算・営業日調整、設定解決、帳票基盤）は本書に記録する。
- STEP L0 で共通層へ移した機能について、`SPEC.md` の該当節は要約と本書への参照に置き換え、二重管理しない。
- ローン層だけ・リース層だけに関わる変更は、それぞれ `SPEC.md` / `SPEC_LEASE.md` に記録する。
- core 変更は、その変更を必要とした層の仕様書に記録する（既存の `PaymentServices.groovy` 修正は `SPEC.md`）。

実装言語の方針：計算・会計・バッチ＝**Java**、CRUD / ステータス遷移 / 画面データ準備＝**entity-auto / Groovy**。

---

## 進捗

| STEP | 内容 | 状態 |
|------|------|------|
| L0 | 共通層の切り出し（`FinanceContract`、契約ロック、審査・与信・延滞の共通化、パッケージ整理） | 未着手 |
| L6 | 延滞・督促へのリース用契約アダプタ登録（共通層側の変更） | 未着手 |

---

## 1. 層構成

| 層 | Java パッケージ | 定義ファイル | 仕様書 |
|----|-----------------|--------------|--------|
| 共通層 | `org.apache.ofbiz.finance.common` | `entitymodel_common*.xml` / `services_common*.xml` | 本書 |
| ローン層 | `org.apache.ofbiz.finance.loan` | 既存の `entitymodel*.xml` / `services*.xml` | `SPEC.md` |
| リース層 | `org.apache.ofbiz.finance.lease` | `entitymodel_lease*.xml` / `services_lease*.xml` | `SPEC_LEASE.md` |

依存は「ローン層 → 共通層」「リース層 → 共通層」のみ。ローン層とリース層は互いを参照しない。

（STEP L0 の実装時に、実際のファイル配置・クラス一覧・エンティティ定義を追記する。）

---

## 運用メモ

（STEP L0 の DB 再構築手順、既存データの `FinanceContract` 移行、seed/demo の投入順、既知の注意点を記録する。）
