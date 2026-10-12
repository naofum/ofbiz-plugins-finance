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
| L1 | リース商品・物件・申込の基盤 | 完了（tests=63 / errors=0 / failures=0） |
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
| Java | `org.apache.ofbiz.finance.lease`（L1：`LeaseProductHelper`） |
| Groovy | `groovyScripts/lease/`（`LeaseProductServices` / `LeaseApplicationServices` / `PrepareLeaseApplication`） |
| entitydef | `entitymodel_lease.xml` |
| servicedef | `services_lease.xml` |
| widget | `FinanceLeaseScreens.xml` / `FinanceLeaseForms.xml`（メニューは共用の `FinanceMenus.xml`） |
| data | `FinanceLeaseTypeData.xml`（seed）/ `FinanceLeaseDemoData.xml`（demo）/ `testdef/data/FinanceTestData.xml` のリース節（テスト） |

リース層の Java / Groovy はローン層（`finance.loan`、`LoanApplication`、`LoanAgreement`）を参照しない。
共通層の部品（共通審査 `createFinanceUnderwriting`、`setLoanUnderwritingStatus`、商品ビュー）だけを使う。

---

## 2. STEP L1 — リース商品・物件・申込

### 2.1 データモデル（`entitymodel_lease.xml`）

| エンティティ | 主キー | 主な項目 | 説明 |
|--------------|--------|----------|------|
| `FinancialProductLease` | productId（FK `FinancialProduct`） | leaseClassEnumId, defaultMonthlyRateFactor, defaultCalcInterestRate, paymentTimingEnumId, residualValueRate, revenueMethodEnumId, taxable | リース商品の条件（`FinancialProduct` と 1:1） |
| `FinancialProductLeaseAssetType` | productId + fixedAssetTypeId | — | 商品ごとの対象物件種別（core `FixedAssetType`）。行がなければ全種別可 |
| `LeaseApplication` | leaseApplicationId（採番） | productId（FK `FinancialProductLease`）, applicantPartyId, statusId, currencyUomId, requestedTermMonths, applicationDate, purpose, comments | リース申込 |
| `LeaseApplicationStatus` | leaseApplicationId + statusId + statusDate | changeByUserLoginId | 状態履歴（作成時の RECEIVED も記録） |
| `LeaseApplicationAsset` | leaseApplicationId + assetSeqId（00001〜） | fixedAssetTypeId, assetName, quantity, estimatedAcquisitionCost, supplierPartyId, comments | 希望物件。見積取得価額は**明細の合計額（税抜、申込通貨）** |

- 期間の上下限・通貨・遅延損害金年率は既存の `FinancialProduct` の項目（`minTermMonths` / `maxTermMonths` / `currencyUomId` / `lateFeeAnnualRate`）を使い、`FinancialProductLease` には持たない。
- リース物件の実体（`FixedAsset`）は L3（購入・検収）で作成する。L1 の申込では物件種別と見積額だけを持つ。

### 2.2 seed（`FinanceLeaseTypeData.xml`）

| 種類 | 値 |
|------|----|
| `FinancialProductType` | `LEASE`（親）、`LEASE_FIN_TRANSFER`（所有権移転）、`LEASE_FIN_NONTRANS`（所有権移転外）、`LEASE_OPERATING`（後続 STEP） |
| `LEASE_CLASS` | `LEASE_CLS_FIN_TRANS` / `LEASE_CLS_FIN_NONTR` / `LEASE_CLS_OPERATING` |
| `LEASE_PAY_TIMING` | `LEASE_PAY_ADVANCE`（前払）/ `LEASE_PAY_ARREARS`（後払） |
| `LEASE_REV_METHOD` | `LEASE_REV_AT_START`（第1法）のみ。第3法は戦略クラスの実装時に追加する |
| `LEASE_APP_STATUS` | `LEASEAPP_RECEIVED` → `SUBMITTED` → `APPROVED` / `REJECTED` → `CONTRACTED`、RECEIVED・SUBMITTED から `CANCELLED`、APPROVED から `REJECTED` |

ID 列は VARCHAR(20) のため、要件書の `LEASE_FINANCE_NON_TRANSFER` 等は 20 文字以内に短縮した。

### 2.3 サービス（`services_lease.xml`）

| サービス | 内容 |
|----------|------|
| `createFinancialProductLease` / `updateFinancialProductLease` | 商品種別が `LEASE` 配下であること。リース区分は商品種別から導出し、不一致は拒否。既定値：前払、第1法、課税 Y、移転外は残価率 0。残価率は 0 以上 1 未満、料率は正、計算利子率は 0 以上、列挙値は該当の EnumerationType のもの。オペレーティングリースは未対応として拒否 |
| `deleteFinancialProductLease` | entity-auto（参照があれば FK で失敗） |
| `addFinancialProductLeaseAssetType` / `removeFinancialProductLeaseAssetType` | entity-auto |
| `createLeaseApplication` | 作成時は必ず `LEASEAPP_RECEIVED`（`statusId` は入力から除外）。リース条件のあるリース商品のみ、期間は商品の上下限内、通貨は未指定なら商品の通貨 |
| `updateLeaseApplication` | RECEIVED 中のみ。商品・期間を再検証 |
| `deleteLeaseApplication` | RECEIVED 中かつ審査なしの場合のみ。希望物件・状態履歴も削除 |
| `setLeaseApplicationStatus` | `StatusValidChange` で検証し履歴を記録。**`LEASEAPP_APPROVED` は希望物件 1 件以上が必要** |
| `createLeaseApplicationAsset` / `updateLeaseApplicationAsset` / `removeLeaseApplicationAsset` | RECEIVED 中のみ。物件種別は商品の対象種別内、数量 > 0、見積取得価額 > 0 |
| `createLeaseUnderwriting` | 申込の存在を確認し、共通層の `createFinanceUnderwriting`（`applicationTypeId=LEASE`、`applicationId`）を呼ぶ。`loanApplicationId` は空 |
| `financeValidateLeaseUnderwritingApproval` | 共通審査の承認時に呼ばれる検証（`underwriting.approval.validator.LEASE`）。**希望物件のない申込の審査は承認できない** |

審査の承認・否決・承認後の変更禁止は共通層の `setLoanUnderwritingStatus` / `updateLoanUnderwriting` をそのまま使う（`SPEC_COMMON.md` 5 章）。
与信チェック（ローン＋リース合算）は L2 で審査承認時・契約作成時に追加する。

### 2.4 画面

| 画面 | 内容 |
|------|------|
| 商品（共通、メニュー「商品」） | `FindFinancialProduct`：業務（ローン/リース）・商品種別で絞込。`EditFinancialProduct`：商品登録・編集。リース商品にはタブ「リース条件」を表示 |
| リース条件 `EditFinancialProductLease` | リース条件の登録・編集、対象物件種別の追加・削除 |
| リース申込（メニュー「リース申込」） | `FindLeaseApplication`：番号・借手・商品・状態で検索。`EditLeaseApplication`：登録・編集・状態変更。`ManageLeaseApplication`：申込ヘッダ（見積取得価額合計）、希望物件の追加・削除（RECEIVED 中）、審査（作成・更新・承認・否決） |

借手・販売店は氏名と ID を併記する。

### 2.5 テスト（`LeaseApplicationTests`）

| テスト | 内容 |
|--------|------|
| `testLeaseProductCrud` | 商品作成（core Product 同時作成）、区分の導出と不一致拒否、残価率・列挙値の検証、既定値、更新、対象物件種別、ローン商品へのリース条件拒否、商品ビューの業務区分 |
| `testLeaseApplicationCrudAndStatus` | 作成時 RECEIVED 固定、通貨の既定、ローン商品・期間外の拒否、更新、不正遷移の拒否、履歴、提出後の変更・削除拒否、物件ごと削除 |
| `testLeaseApplicationAssets` | 明細採番、対象外種別・0 円の拒否、更新・削除、提出後の追加・削除拒否 |
| `testLeaseApprovalRequiresAssets` | 物件なし申込の審査承認・申込承認の拒否、`applicationTypeId=LEASE` の審査、承認後の変更拒否、ローン側の審査に出ないこと |

ローン業務への影響がないことは既存のローンテスト（全件成功）で確認した。

### 2.6 検証

| 検証項目 | 結果 |
|----------|------|
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入（`--load-data readers=seed --load-data component=finance`） | 新規 5 テーブル作成、`FinanceLeaseTypeData.xml` 27 件 |
| `gradlew "ofbiz --test component=finance"` | tests=63 / errors=0 / failures=0（リース 4 件を含む） |
| demo 投入（`--load-data readers=demo --load-data component=finance`） | `FinanceLeaseDemoData.xml` 14 件、BUILD SUCCESSFUL |
| 層の依存 | リース層からローン層、共通層 Java からリース層への参照なし（grep） |

---

## 運用メモ

- **seed の投入**：L1 で `FinanceLeaseTypeData.xml` を追加した。既存環境では `gradlew "ofbiz --load-data readers=seed --load-data component=finance"` を実行してからテスト・画面操作を行う（未投入だとリース商品種別・申込状態がなく失敗する）。
- **demo**：`FinanceLeaseDemoData.xml`（事務機器ファイナンスリース・所有権移転外 `DEMO_LEASEPROD1`、借手 `DEMO_LESSEE1`、販売店 `DEMO_LS_SUPPLIER1`、受付中の申込 `DEMO_LEASEAPP1` と希望物件 2 件）。
