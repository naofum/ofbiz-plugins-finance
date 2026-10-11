# Finance プラグイン 仕様 — リース層（STEP L1〜）

Apache OFBiz 上に構築する独立ファイナンスモジュール（`plugins/finance`）のうち、
**リース業務（STEP L1〜）** の詳細仕様・運用記録をまとめる。

| 文書 | 対象 |
|------|------|
| `REQUIREMENTS.md` | ローン業務の要件（STEP 1〜10） |
| `REQUIREMENTS_LEASE.md` | 共通層の切り出し（STEP L0）とリース業務の要件（STEP L1〜） |
| `SPEC_COMMON.md` | 共通層の詳細仕様・運用記録 |
| `SPEC.md` | ローン層（STEP 1〜10）の詳細仕様・運用記録 |
| **`SPEC_LEASE.md`（本書）** | リース層（STEP L1〜）の詳細仕様・運用記録 |

記録の振り分け：

- リース層だけに関わる仕様（リース申込・見積・契約・確定リース料表・物件・検収・リース会計・消費税・満了処理）を本書に記録する。
- 共通層（`FinanceContract`、契約ロック、審査、与信合算、延滞・督促、計算部品）は `SPEC_COMMON.md` に記録し、本書から参照する。
- core 変更がリース業務のために必要になった場合は、理由・影響範囲・代替案・検証結果を本書に記録する。

実装言語の方針：計算・会計・バッチ＝**Java**、CRUD / ステータス遷移 / 画面データ準備＝**entity-auto / Groovy**。

---

## 進捗

| STEP | 内容 | 状態 |
|------|------|------|
| L1 | リース商品・物件・申込の基盤 | 未着手 |
| L2 | リース見積・審査・契約 | 未着手 |
| L3 | 物件の購入・検収・リース開始（第1法の開始仕訳） | 未着手 |
| L4 | 月次会計・リース料請求・入金消込（繰延リース利益戻入） | 未着手 |
| L5 | 消費税 | 未着手 |
| L6 | 延滞・督促（リース用契約アダプタ。共通層側は `SPEC_COMMON.md`） | 未着手 |
| L7 | 期間満了処理（返還・所有権移転） | 未着手 |
| L8 | 帳票・照会 | 未着手 |

前提：STEP L0（共通層の切り出し、`SPEC_COMMON.md`）の完了後に着手する。

---

## 1. リース層の構成

| 種別 | 配置 |
|------|------|
| Java | `org.apache.ofbiz.finance.lease` |
| entitydef | `entitymodel_lease*.xml` |
| servicedef | `services_lease*.xml` |
| widget | `FinanceLease*Screens.xml` / `FinanceLease*Forms.xml` |
| data | `FinanceLease*TypeData.xml`（seed）/ `FinanceLease*DemoData.xml`（demo） |

（STEP L1 の実装時に、実際のファイル配置・エンティティ定義を追記する。）

---

## 運用メモ

（STEP L1 以降の DB 再構築手順、seed/demo の投入順、既知の注意点を記録する。）
