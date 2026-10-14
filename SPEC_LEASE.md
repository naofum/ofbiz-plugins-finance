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
| L2 | リース見積・審査・契約 | 完了（tests=70 / errors=0 / failures=0） |
| L3 | 物件の購入・検収・リース開始（第1法の開始仕訳） | 完了（finance テスト errors=0 / failures=0、testcase 44 件） |
| L4 | 月次会計・リース料請求・入金消込（繰延リース利益戻入） | 完了（tests=97 / errors=0 / failures=0） |
| L5 | 消費税 | 完了（tests=107 / errors=0 / failures=0） |
| L6 | 延滞・督促（リース用契約アダプタ。共通層側は `SPEC_COMMON.md`） | 完了（tests=110 / errors=0 / failures=0） |
| L7 | 期間満了処理（返還・所有権移転）。作業管理（WorkEffort）を共通層に導入（`REQUIREMENTS_LEASE.md` 3.5） | 完了（tests=119 / errors=0 / failures=0） |
| L8 | 帳票・照会 | 完了（tests=121 / errors=0 / failures=0） |
| L9 | リース料の構成要素（維持管理費用相当額） | 完了（tests=126 / errors=0 / failures=0） |
| L10 | 固定資産税（償却資産税） | 完了（tests=134 / errors=0 / failures=0） |

前提：STEP L0（共通層の切り出し、`SPEC_COMMON.md`）の完了後に着手する。

---

## 1. リース層の構成

| 種別 | 配置 |
|------|------|
| Java | `org.apache.ofbiz.finance.lease`（L1：`LeaseProductHelper`、L2：`LeaseCalculator` / `LeaseContractAdapter`、L3：`LeaseAcquisitionServices` / `LeaseRevenueStrategy`、L4：`LeaseAccountingServices` / `LeaseProfitAllocator`、L5：`LeaseTaxHelper`、L9：`LeaseComponentCalculator`、L10：`PropertyTaxValuation` / `LeasePropertyTaxServices`） |
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

## 3. STEP L2 — リース料計算・見積・審査・契約

### 3.1 リース料計算（`LeaseCalculator`、Java・エンティティ非依存）

| 方式 | `calcMethodEnumId` | 月額リース料 | 計算利子率 |
|------|--------------------|--------------|------------|
| 料率方式 | `LEASE_CALC_RATE_FCTR` | 取得価額 × 月額リース料率（通貨別丸め。JPY は切り捨て） | リース料と残価の現在価値＝取得価額となる月利を二分法で解き、×12 で年率化 |
| 利子率方式 | `LEASE_CALC_INT_RATE` | （取得価額 − 残価の現在価値）÷ 年金現価係数。**通貨の桁で切り上げ**（回収不足を防ぐ） | 入力値（月利＝年率 ÷ 12） |

- 前払（`LEASE_PAY_ADVANCE`）は支払時点 0〜n−1、後払は 1〜n で現在価値を計算する。
- 各回を利息法で配分する：利息相当額＝前回後の元本残高 × 月利（通貨別丸め）、元本回収額＝リース料 − 利息相当額。前払の第1回は利息 0。
- **最終回で端数を調整**し、元本回収額の合計＝取得価額 − 残価、最終の元本残高＝残価とする（前払・残価ありの 1 か月分の利息差もここで吸収）。
- 利息相当額合計＝リース料総額 −（取得価額 − 残価）。
- 消費税額＝各回リース料 × 税率（通貨別丸め、表示用）。税率は商品が課税（`taxable=Y`）のとき `finance.properties` の `lease.tax.rate`（既定 0.10）、非課税は 0。計上は L5。
- 拒否する入力：取得価額 ≤ 0、期間 ≤ 0、残価が負または取得価額以上、料率 ≤ 0、利子率 < 0、**料率が低く「リース料総額＋残価 < 取得価額」となる場合**。

### 3.2 データモデル（`entitymodel_lease.xml` に追加）

| エンティティ | 主キー | 内容 |
|--------------|--------|------|
| `LeaseQuote` | leaseQuoteId | 申込、状態（`LEASE_QUOTE_STATUS`）、計算方式、取得価額、残価、期間、支払時期、月額リース料、料率、計算利子率、リース料総額、利息相当額合計、税率、消費税総額、開始予定日、初回支払日 |
| `LeaseQuoteAsset` | leaseQuoteId + assetSeqId | 見積時点の物件明細（申込の希望物件を複写） |
| `LeasePaymentSchedule` | leaseQuoteId + installmentNumber | 見積リース料表：支払期日、対象期間（periodFrom/Thru）、リース料、元本回収額、利息相当額、消費税、元本残高 |
| `LeaseAgreement` | leaseAgreementId | `financeContractId`（一意）、申込、見積（一意）、状態（`LEASE_AGR_STATUS`）、貸手組織、借手、販売店（全明細が同一の場合）、商品、リース区分・収益計上方式（商品から）、見積条件のスナップショット、契約日、初回支払日（暫定）、リース開始日・終了日（L3） |
| `LeaseAgreementPaymentSchedule` | leaseAgreementId + scheduleRevisionNumber + installmentNumber | 契約確定リース料表（契約時 revision 1）。請求はこれだけを参照する |
| `LeaseAgreementAsset` | leaseAgreementId + assetSeqId | 契約物件。`fixedAssetId` は L3（購入・検収）で設定 |

- 取得価額は**見積時点の希望物件の見積取得価額合計**とし、入力で上書きしない。残価は未指定なら取得価額 × 商品の残価率。
- 日付：開始予定日（未指定は当日）、前払は初回支払日＝開始日、後払は開始日＋1 か月。第 k 回の期日は初回支払日＋(k−1) か月を商品の期日調整規則（`dueDateConventionEnumId`、STEP 10）で営業日調整。対象期間は開始日＋(k−1) か月〜開始日＋k か月の前日。リース開始日（検収日）が確定した時点で L3 が日付を確定する（見積・契約の期日は暫定）。

### 3.3 seed（`FinanceLeaseTypeData.xml` に追加）

| 種類 | 値 |
|------|----|
| `LEASE_CALC_METHOD` | `LEASE_CALC_RATE_FCTR` / `LEASE_CALC_INT_RATE` |
| `LEASE_QUOTE_STATUS` | `LEASEQT_CREATED` → `PRESENTED` → `ACCEPTED` / `REJECTED` |
| `LEASE_AGR_STATUS` | `LEASEAGR_CONTRACTED`（検収待ち）/ `ACTIVE` / `COMPLETED` / `CANCELLED`。遷移は L2 では `CONTRACTED → CANCELLED` のみ（`→ ACTIVE` は L3 の検収、`→ COMPLETED` は L7 で追加） |

### 3.4 サービス（`services_lease.xml`、`groovyScripts/lease/LeaseQuoteServices.groovy`）

| サービス | 内容 |
|----------|------|
| `createLeaseQuote` | 申込が RECEIVED / SUBMITTED / APPROVED、希望物件 1 件以上、期間が商品の範囲内。料率と利子率の同時指定は拒否。両方未指定なら商品の既定料率、なければ既定利子率。見積・物件明細・リース料表を作成（以後変更不可。更新サービスは持たない） |
| `setLeaseQuoteStatus` | `StatusValidChange` で検証。**同一申込で受理済み見積は 1 件だけ** |
| `createLeaseAgreement` | 承認済み申込・受理済み見積・承認済み LEASE 審査（承認額 ≥ 取得価額）・完全なリース料表（行数＝期間、元本回収合計＝取得価額−残価、最終残高＝残価）・物件明細を検証し、与信を確認。`FinanceContract`（LEASE、`FINCTR_CONTRACTED`、元本＝取得価額）、`LeaseAgreement`（`LEASEAGR_CONTRACTED`）、確定リース料表 revision 1、契約物件を作成し、申込を `LEASEAPP_CONTRACTED` にする。**同一見積からの契約は拒否**（一意索引でも防止） |
| `setLeaseAgreementStatus` | `StatusValidChange` で検証し、`FinanceContract` の状態へ反映（CONTRACTED/ACTIVE/COMPLETED/CANCELLED → `FINCTR_*`） |

審査承認時の検証（`financeValidateLeaseUnderwritingApproval`）に、**承認額（取得価額）の入力必須**と**与信チェック**を追加した。
与信は共通層の `financeValidateCustomerCreditLimit`（`SPEC_COMMON.md` 3 章）で、審査承認時と契約作成時に行う。

### 3.5 契約種別アダプタ

`LeaseContractAdapter` を L2 で `contract.adapter.LEASE` に登録した（要件では L6）。顧客照会の契約番号表示と
`ViewFinanceContract` からリース契約画面への遷移に必要なため。延滞算出（`computeDelinquency`）と未入金請求書の取得は
L6 で実装し、それまでは「延滞なし」「請求書なし」を返す（延滞バッチは ACTIVE の契約だけを処理し、L2 ではリース契約は ACTIVE にならない）。

### 3.6 画面・帳票

| 画面 | 内容 |
|------|------|
| `ManageLeaseApplication`（追加） | 見積作成フォーム（期間・料率・利子率・残価・支払時期・開始予定日）、見積一覧（提示・受理・否決・契約作成）、契約へのリンク |
| `ViewLeaseQuote` | 見積条件、物件明細、リース料表（9 列）、見積書 PDF |
| `FindLeaseAgreement`（メニュー「リース契約」） | 契約番号・借手・商品・状態で検索 |
| `ManageLeaseAgreement` | 契約条件、物件（固定資産欄つき）、契約確定リース料表（最新 revision）、契約取消（CONTRACTED 中、確認あり）、契約書 PDF、顧客照会へのリンク |
| PDF | `LeaseReport.jrxml` 1 本を見積書・契約書で共用（`PrepareLeaseReport.groovy` がタイトル・番号を切替）。リース料・消費税・税込合計・元本回収額・利息相当額・元本残高の 8 列。期日が暫定である旨を注記 |

### 3.7 テスト（`LeaseQuoteAgreementTests`）

| テスト | 内容 |
|--------|------|
| `testRateFactorCalculation` | 料率方式・前払・残価なし：月額 18,000、元本回収合計＝取得価額、第1回利息 0、利息の逓減、計算利子率の逆算（約 3.2%）、消費税 |
| `testInterestRateCalculationWithResidual` | 利子率方式・後払・残価あり：第1回利息、元本回収合計＝取得価額−残価、最終残高＝残価、料率で再計算した利率の一致 |
| `testCalculationEdgeCases` | 料率不足・残価≧取得価額の拒否、利率 0 の切り上げと端数調整 |
| `testLeaseQuoteCreationAndStatus` | 見積の内容・期日・対象期間・物件明細、利子率方式（後払・残価）、同時指定・回収不足・期間外の拒否、申込物件変更後も見積不変、状態遷移、受理済み 1 件制約、取消申込への見積拒否 |
| `testCreateLeaseAgreement` | 契約・`FinanceContract`・確定リース料表・物件・申込状態、二重契約拒否、顧客照会でのリース契約表示と与信額、ACTIVE への直接遷移拒否、取消の共通状態反映 |
| `testLeaseAgreementPreconditions` | 見積未受理・申込未承認・審査なし・承認額不足の拒否、承認額なしの審査承認拒否 |
| `testLeaseCreditLimit` | 与信枠（毎回新しい借手を作成）：審査承認時の超過拒否、他契約による超過での契約拒否、CONTRACTED 契約の与信額算入、取消後の与信額 0 |

### 3.8 検証

| 検証項目 | 結果 |
|----------|------|
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 6 テーブル作成、BUILD SUCCESSFUL |
| `gradlew "ofbiz --test component=finance"` | tests=70 / errors=0 / failures=0（リース見積・契約 7 件を含む。連続 2 回実行で同結果） |
| 画面（OFBiz 起動・admin で HTTP 確認） | 申込→物件→提出→審査承認→申込承認→見積→提示→受理→契約作成を画面のリクエストで実行し、`ManageLeaseApplication` / `ViewLeaseQuote` / `ManageLeaseAgreement` / `FindLeaseAgreement` / 商品画面 / 顧客照会（リース契約表示、`ViewFinanceContract` → `ManageLeaseAgreement` 遷移）を確認 |
| PDF | 見積書・契約書とも生成（PDF 1.5、2 ページ）。日本語はローンの帳票と同じ CJK フォント設定（`HeiseiKakuGo-W5`、非埋め込みの Adobe CID フォント）のため、Asian Font Pack のない表示環境では日本語が表示されない（ローン帳票も同じ。既存の制約） |

---

## 4. STEP L3 — 物件の購入・販売店支払・検収（リース開始）

### 4.1 業務フロー

1. 契約（`LEASEAGR_CONTRACTED`）後、契約物件ごとに **購入を記録** する（`recordLeaseAssetPurchase`）。
   - 販売店の仕入請求書（core `Invoice`、`PURCHASE_INVOICE`）を作成して READY にする。core が「借 リース物件仮勘定 ／ 貸 買掛金」を計上する（明細の `overrideGlAccountId` を仮勘定にする）。
   - リース会社所有の `FixedAsset`（種別・名称・取得日・取得価額・通貨、`partyId`＝貸手）を作成し、契約物件に `fixedAssetId` を設定する。
   - 物件状態：`LSAST_PENDING` → `LSAST_PURCHASED`。
2. **販売店へ支払う**（`payLeaseAssetPurchase`、任意・検収とは独立）。core の `VENDOR_PAYMENT`（`PMNT_SENT`）を作成して仕入請求書に消し込む。出金・消込の仕訳と、請求書の PAID 化は core が行う。**支払日は未来日不可**（core は効力日が現在以前の支払しか消込済みとみなさないため）。
3. **検収**（`acceptLeaseAgreement`）。全物件の購入済みが必要。検収日は契約日・購入日以降とする。
   - **検収日＝リース開始日**。確定リース料表を新しい revision（例：契約時 1 → 検収時 2）として作り直す。金額は変えず、支払期日・対象期間だけを開始日基準で付け直す（前払は初回期日＝開始日、後払は開始日＋1 か月。期日の営業日調整は STEP 10 の規則）。
   - 契約に開始日・終了日（開始日＋期間−1 日）・初回支払日を設定し、`LEASEAGR_ACTIVE` にする（`FinanceContract` も `FINCTR_ACTIVE`）。物件状態は `LSAST_LEASED` になる。
   - 収益計上方式の開始仕訳を 1 回だけ計上し、`LeaseAcceptance` に記録する。**同じ契約を再度検収しても、既存の検収結果を返すだけ**（冪等。新しい revision も仕訳も作らない）。
- 状態サービス `setLeaseAgreementStatus` からの `ACTIVE` への変更は拒否する（開始は検収でのみ行う）。物件の購入後は契約を取り消せない（物件の処分は対象外）。
- 契約単位の処理ロックは `FinanceContractHelper.lock` で取る。

### 4.2 データモデル

| エンティティ | 主キー | 内容 |
|--------------|--------|------|
| `LeaseAssetPurchase` | leaseAgreementId + assetSeqId | 販売店、仕入請求書、販売店の請求書番号、購入日、金額（＝契約物件の取得価額）、通貨、固定資産、支払、支払日 |
| `LeaseAcceptance` | leaseAgreementId | 検収日（＝開始日）、検収者（既定は借手）、備考、開始時の revision、開始仕訳、受領書の発行日（L8 で使用）、作成者 |
| `LeaseAgreementAsset`（追加列） | — | `assetStatusId`（`LEASE_ASSET_STATUS`：`LSAST_PENDING` / `PURCHASED` / `LEASED` / `RETURNED` / `TRANSFERRED`。返還・譲渡は L7） |

seed：`LEASEAGR_CONTRACTED → LEASEAGR_ACTIVE` の遷移と、リース用の GL 科目タイプ・科目（下表）を追加した。
会社（`Company`）への科目の割当（`GlAccountTypeDefault` / `GlAccountOrganization`）は demo データとテストデータに入れた。

| GL 科目タイプ | 科目 | 区分 | 用途 |
|---------------|------|------|------|
| `LEASE_INVESTMENT` | 128500 | 流動資産 | リース投資資産（所有権移転外） |
| `LEASE_RECEIVABLE` | 128600 | 流動資産 | リース債権（所有権移転） |
| `LEASE_ASSET_SUSPENSE` | 147500 | 流動資産 | リース物件仮勘定（購入〜開始） |
| `LEASE_DEFER_PROFIT` | 128700 | 資産の控除（CONTRA_ASSET） | 繰延リース利益 |
| `LEASE_SALES` | 403500 | 収益 | リース売上高 |
| `LEASE_COST_OF_SALES` | 503500 | 売上原価 | リース売上原価 |
| `LEASE_DEFER_PROV` | 503600 | 売上原価 | 繰延リース利益繰入 |

### 4.3 開始仕訳（第1法、`LeaseRevenueStrategy.AtStart`）

| 借方 | 貸方 | 金額 |
|------|------|------|
| リース投資資産（移転外）／リース債権（移転） | リース売上高 | リース料総額（税抜） |
| リース売上原価 | リース物件仮勘定 | 取得価額 − 残価 |
| リース投資資産／リース債権 | リース物件仮勘定 | 残価（ある場合） |
| 繰延リース利益繰入 | 繰延リース利益 | 利息相当額（＝リース料総額 −（取得価額 − 残価）） |

- 開始時点で「売上高 − 売上原価 ＝ 繰延リース利益」となり、仮勘定は購入時の借方と同額が貸方に振り替わって残高 0 になる。投資資産の残高はリース料総額＋残価。
- 残価があるときは、売上原価を取得価額−残価とし、残価分は仮勘定から投資資産へ振り替える方式とした。**第1法での残価の扱い（売上原価・売上高の測定）は要件 1.4 のとおり経理部門・会計監査人の確認事項**で、L4 着手前に確定する。
- 収益計上方式は `revenueMethodEnumId` で戦略クラスを選ぶ（`LeaseRevenueStrategy.REGISTRY`）。L3 では第1法の開始仕訳だけを実装した。月次の戻し入れは L4、第3法は後続。
- 仕訳は共通層の `FinanceGlHelper.postTransaction` で 1 取引・複数明細として計上・転記する（`SPEC_COMMON.md` 9 章）。

### 4.4 画面

`ManageLeaseAgreement` に次を追加した。物件一覧（状態・固定資産・仕入請求書・販売店支払の列、行ごとの「購入記録」「販売店へ支払」）、検収フォーム（検収日・検収者・備考、全物件の購入後に表示）、検収結果（検収日・検収者・revision・開始仕訳）。
リース料表の欄には最新の revision を表示する。契約取消のボタンは、物件の購入後は表示しない。

### 4.5 テスト（`LeaseAcceptanceTests`。USD 商品 `TEST_LEASEPROD_USD` を使い、テストごとに新しい借手を作る）

| テスト | 内容 |
|--------|------|
| `testAssetPurchaseAndSupplierPayment` | 仕入請求書（PURCHASE_INVOICE、READY）と仕訳（借 仮勘定 ／ 貸 買掛金、転記済み）、固定資産、物件の紐付けと状態、二重購入・購入後の取消の拒否、未来日支払の拒否、支払（VENDOR_PAYMENT・SENT、請求書 PAID、仕訳転記済み）、二重支払の拒否 |
| `testAcceptanceStartsLease` | 状態サービスでの開始の拒否、未購入・購入日前の検収の拒否、ACTIVE 化・開始日・終了日・初回支払日・共通状態、revision 2 の期日・期間（金額は revision 1 と同じ）、開始仕訳（貸借一致、各科目の金額、売上高−売上原価＝繰延リース利益、仮勘定の消滅）、再検収で同じ結果を返すこと、開始後の購入拒否 |
| `testTransferLeaseWithResidualStartEntries` | 所有権移転・残価 10%：リース債権科目、借方＝リース料総額＋残価、売上原価＝取得価額−残価、貸借一致 |

検収前の請求拒否は、請求機能を作る L4 でテストする。

### 4.6 検証

| 検証項目 | 結果 |
|----------|------|
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 2 テーブル、`LEASE_AGREEMENT_ASSET` に列追加、BUILD SUCCESSFUL |
| `gradlew "ofbiz --test component=finance"` | errors=0 / failures=0（testcase 44 件、うちリース検収 3 件） |
| demo 投入 | BUILD SUCCESSFUL |
| 画面（OFBiz 起動） | L2 で作った JPY のデモ契約 `10010` で、購入記録 → 販売店支払 → 検収を画面のリクエストで実行した。ACTIVE 化、revision 2、開始仕訳 `11043`（6 明細、USD 換算後も貸借一致）、仕入請求書・支払の仕訳がすべて転記済みであることを DB で確認した |

---

## 5. STEP L4 — 月次会計・リース料請求・入金消込

実装：`LeaseAccountingServices`（サービス）、`LeaseProfitAllocator`（戻入額の計算、エンティティ非依存）、`LeaseRevenueStrategy`（方式ごとの仕訳）。
すべての処理は契約単位で `FinanceContractHelper.lock` を取り、**検収済み（`LEASEAGR_ACTIVE`）の契約だけ** を対象にする（検収前の請求・戻入・入金は拒否）。

### 5.1 データモデル

| エンティティ | 主キー | 内容 |
|--------------|--------|------|
| `LeaseInstallmentInvoice` | leaseAgreementId + installmentNumber | 請求した回と core `Invoice`（一意）。請求した revision、期日、リース料・元本回収額・利息相当額・消費税・請求額（税込）の写し |
| `LeaseProfitRecognition` | leaseProfitRecognitionId（採番） | 繰延リース利益の戻入。期間（fromDate〜thruDate、日単位・両端含む）、当期額、累計額、仕訳。契約＋fromDate で一意 |
| `LeasePaymentReceipt` | leasePaymentReceiptId（採番） | 入金（core `Payment`、一意）、指定した請求書（任意）、金額、入金日 |

seed（`FinanceLeaseTypeData.xml`）：請求明細種別 `LEASE_PAYMENT`（リース料）・`LEASE_CONS_TAX`（消費税）と `SALES_INVOICE` への InvoiceItemTypeMap、
GL 科目タイプ `LEASE_PROFIT_RELEASE`（403600 繰延リース利益戻入益、収益）・`LEASE_TAX_PAYABLE`（224500 仮受消費税（リース）、流動負債）。
`Company` への割当は demo・テストデータに追加した。売掛金は core の `ACCOUNTS_RECEIVABLE`（Company は 120000）を使う。

### 5.2 請求（`createLeaseInstallmentInvoice`）

- 現在の revision の確定リース料表（検収で作り直したもの）の 1 回分を、core の `SALES_INVOICE` として作成し READY にする。期日が当日以前の回だけ。同じ回の再請求は既存の請求書を返す（冪等）。
- 明細と仕訳（第1法）：

| 借方 | 貸方 | 金額 |
|------|------|------|
| 売掛金（core が計上） | リース投資資産（移転外）／リース債権（移転）（明細 `LEASE_PAYMENT` の `overrideGlAccountId`） | リース料（税抜） |
| 売掛金（core が計上） | 仮受消費税（明細 `LEASE_CONS_TAX` の `overrideGlAccountId`） | 消費税（リース料表の `taxAmount`） |

- 売上は開始時に計上済みのため、請求では計上しない。貸方科目は戦略クラスの `billingCreditAccountType` で決める。
- 消費税は core の税明細（`ITM_SALES_TAX` / `TaxAuthority`）を使わず、L2 の表示用税額をそのまま独自の明細種別で計上した。税率の固定・税務当局・課税時期は L5 で扱う。

### 5.3 繰延リース利益の戻入（`recognizeLeaseProfit`）

- 各回の利息相当額（利息法、L2 のリース料表）を、その利息が生じるリース期間に割り当てる。
  - 後払：第 k 回の利息相当額は第 k 期間に帰属する。
  - 前払：第 k 回（k ≥ 2）の利息相当額は、その回の期日で終わる第 k−1 期間に帰属する（第 1 回は利息 0）。したがって前払では第 n−1 期間の末日で全額の戻入が終わる。
- ある日までの累計戻入額 ＝ 終了した期間の利息相当額の合計 ＋ 進行中の期間の日割り（通貨別丸め）。各回は「累計（今回の計上日）− 計上済み累計」を計上するので、**月中の再実行は直前計上日の翌日から増分だけを計上し、二重計上しない**。累計は最終の帰属日に利息相当額合計（＝開始時の繰延リース利益）と一致する（端数は最終期間で吸収）。
- 記録は暦月ごとに 1 件（例：開始日〜月末、翌月 1 日〜計上日）。計上日が最終の帰属日より後なら最終の帰属日までとする。0 円の期間は記録だけを作り、仕訳は作らない。
- 仕訳：借 繰延リース利益（128700）／ 貸 繰延リース利益戻入益（403600）。仕訳日は記録の thruDate。
- 戦略クラスの `recognitionEntries` で方式ごとの仕訳を返す（第3法を追加するときはここと `billingCreditAccountType` を実装する）。

### 5.4 月次バッチ（`runLeaseMonthlyAccountingBatch`）

全 ACTIVE リース契約（または指定契約）について、①処理日までに期日の来た回を期日順に請求し、②処理日までの繰延リース利益を戻入する。
請求と戻入は互いの結果に依存しない（戻入はリース料表の利息相当額から計算し、請求・入金の有無に左右されない）ので、ローンの「期日まで計上 → 請求 → 残期間の計上」の 3 段は不要である。再実行は請求・戻入とも何も作らない。
処理件数（契約・請求書・戻入記録）を返す。画面は `FindLeaseAgreement` の「リース月次バッチ」。

### 5.5 入金消込（`receiveLeasePayment`）と回収状況（`getLeaseAgreementBalance`）

- core の `CUSTOMER_PAYMENT`（`PMNT_RECEIVED`、支払方法の既定 `EFT_ACCOUNT`）を作成し、指定した請求書へ、指定がなければ **期日の古い未入金のリース請求書から順に** `PaymentApplication` で消し込む。入金額が対象請求書の未消込額を超える場合は拒否する。入金・消込の仕訳は core が作り、未転記分を転記する。`LeasePaymentReceipt` に記録する。
- 他契約・取消済み・貸倒済み・通貨や借手が違う請求書への消込は拒否する。
- 回収状況は **有効な消込（`PMNT_RECEIVED` / `PMNT_CONFIRMED` の Payment の `PaymentApplication`）と請求した回の金額** から毎回算定する（スナップショットは持たない）。1 請求書の消込額は **消費税 → 利息相当額 → 元本回収額** の順に充当する。
  - 未回収元本 ＝ 取得価額 − 充当された元本回収額の累計。**請求しただけでは減らない**。
  - ほかに請求額・消込額・未入金額・未請求リース料・戻入累計・繰延リース利益残高を返す。
- 消込解除は core の `removePaymentApplication`（core 修正済み、`SPEC.md` 14 章）をそのまま使い、請求書は READY に戻り、回収状況にも自動で反映される。
- 遅延損害金の先行充当は L6（延滞）で扱う。

### 5.6 画面

`ManageLeaseAgreement`（検収後）に、回収状況、リース料請求（回を指定）、繰延リース利益戻入（計上日）、入金受付（金額・請求書・入金日）のフォームと、
請求書一覧（未入金額・状態）、戻入一覧（期間・額・累計・仕訳）、入金記録の一覧を追加した。フォームは ACTIVE の間だけ表示する。

### 5.7 テスト（`LeaseAccountingTests`。USD 商品 `TEST_LEASEPROD_USD`、テストごとに新しい借手、開始日は当日より前）

| テスト | 内容 |
|--------|------|
| `testBillingFromConfirmedSchedule` | 検収前の請求・戻入・バッチ・入金の拒否、請求額＝確定リース料表（revision 2）、請求書（SALES_INVOICE・READY・期日・明細 2 行）、GL（借 売掛金 ／ 貸 投資資産・仮受消費税、売上なし、貸借一致）、再請求の冪等性、期日前の請求拒否、バッチでの期日到来分の一括請求と再実行で 0 件、請求だけでは未回収元本が減らないこと |
| `testDeferredProfitReleaseOverFullTerm` | 利子率方式 12 か月：前払の帰属（第 1 期間＝第 2 回の利息）、月中実行の暦月分割、同日再実行で 0、翌日からの増分、戻入仕訳、満了後のバッチで全 12 回請求・**戻入累計＝開始時の繰延リース利益**・期間の連続（重複なし）、投資資産の開始借方＝請求の貸方合計、満了後の再実行で 0 件 |
| `testPaymentApplicationAndOutstandingPrincipal` | 0 円・他請求書・超過入金の拒否、一部入金（消費税 → 元本の充当）、請求書指定なしで古い順に 2 件消込（PAID）、利息・元本の充当額と未回収元本、消込解除で請求書 READY・未回収元本と未入金額が戻ること |

### 5.8 検証

| 検証項目 | 結果 |
|----------|------|
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 3 テーブル作成、`FinanceLeaseTypeData.xml` 73 件、BUILD SUCCESSFUL |
| `gradlew "ofbiz --test component=finance"` | tests=97 / errors=0 / failures=0（リース会計 3 件を含む） |
| demo 投入 | `FinanceLeaseDemoData.xml` 32 件、BUILD SUCCESSFUL |
| 画面 | 未確認（OFBiz を起動しての画面操作は行っていない） |

### 5.9 要確認・制約

- **戻入の帰属（前払）**：前払の第 k 回の利息相当額を第 k−1 期間に帰属させた（利息は期間の経過で生じ、期日に回収される）。経理部門の確認事項とする。
- **残価**：残価がある契約では、投資資産の開始借方（リース料総額＋残価）のうち残価分は請求では減らず、満了時（L7）の処分で精算する。
- **消費税**：L4 は表示用税額（`lease.tax.rate`）を仮受消費税で計上するだけ。課税時期（開始時一括／各回）・税率の固定・core 税明細との関係は L5。
- **外貨建て**：契約通貨と貸手の基準通貨が違う場合、戻入は `FinanceGlHelper` で換算するが、請求・入金の仕訳は core の換算（4 桁丸め）による。L3 の運用メモと同じ差が生じうる。

---

## 6. STEP L5 — 消費税

実装：`LeaseTaxHelper`（税率・計上時期の解決、税額の配分）。検収（`acceptLeaseAgreement`）で税率と計上時期を契約に固定し、請求（`createLeaseInstallmentInvoice`）が固定した内容で税明細を作る。

### 6.1 税率の解決と固定

- 税率は core の `TaxAuthorityRateProduct`（`taxAuthorityRateTypeId=SALES_TAX`）から取る。対象は、リース商品が属する商品カテゴリ（`ProductCategoryMember`）の行のうち、`finance.properties` の `lease.tax.authority.geoId`（既定 `JPN`）の税務当局のもので、**リース開始日（検収日）に有効なもの**（fromDate 以前・thruDate より前）。複数あれば fromDate の新しいもの。`taxPercentage`（%）を 100 で割って率にする。
- 非課税商品（`taxable=N`）は 0。該当する税率行がない商品は `lease.tax.rate`（既定 0.10）を使い、税務当局なしとする（L2〜L4 と同じ挙動）。
- 検収時に、税率・税務当局（`taxAuthPartyId` / `taxAuthGeoId` / `taxAuthorityRateSeqId`）・計上時期を `LeaseAgreement` に保存し、確定リース料表の新 revision の消費税額と `totalTaxAmount` を固定した税率で計算し直す。**以後の請求は請求日の税率ではなくこの固定値を使う**（税率改定をまたぐ契約も開始時の税率のまま。経過措置を想定）。
- 見積の税率（表示用）も、見積の開始予定日で同じ方法で解決する。

### 6.2 計上時期（`LEASE_TAX_TIMING`）

商品の `FinancialProductLease.taxTimingEnumId` → `lease.tax.timing`（既定 `LEASE_TAX_INSTALL`）の順で決め、検収時に契約へ固定する。

| 計上時期 | 開始時 | 請求時の税明細 | 請求時の仕訳（税） |
|----------|--------|----------------|--------------------|
| 各回（`LEASE_TAX_INSTALL`、税務当局あり） | なし | core の `ITM_SALES_TAX`（税務当局・税率行つき） | 貸 `TaxAuthorityGlAccount` の科目（Company は 224500 仮受消費税）。core の税集計の対象になる |
| 各回（税務当局なし） | なし | `LEASE_CONS_TAX` | 貸 仮受消費税（L4 と同じ） |
| 開始時一括（`LEASE_TAX_AT_START`） | 借 未収消費税（リース、128800）／ 貸 仮受消費税（224500）：消費税総額。`LeaseAcceptance.startTaxAcctgTransId` に記録 | `LEASE_CONS_TAX` | 貸 未収消費税（リース）。仮受消費税は再計上しない。満了時に未収消費税の残高は 0 |

- 各回の税務当局つきの経路では、請求書の税明細と GL の仮受消費税が一致する（core の売上請求の仕訳が税務当局ごとに貸方を作る）。

### 6.3 端数処理

- 通貨別端数処理（JPY は切り捨て、USD は四捨五入 2 桁）を消費税にも適用する。
- 各回：回ごとに「リース料 × 税率」を丸める。消費税総額は各回の合計（明細単位の端数は総額に合わせない）。
- 開始時一括：消費税総額＝「リース料総額 × 税率」を 1 回だけ丸め、各回は回ごとの丸め額、**最終回で総額との差を調整**する（各回の合計＝開始時に計上した総額）。

### 6.4 seed・demo・テストデータ

- seed：`LEASE_TAX_TIMING`（`LEASE_TAX_INSTALL` / `LEASE_TAX_AT_START`）、GL 科目タイプ `LEASE_TAX_RECEIVABLE`（128800 未収消費税（リース）、流動資産）。
- `FinancialProductLease.taxTimingEnumId`、`LeaseAgreement.taxTimingEnumId` / `taxAuthPartyId` / `taxAuthGeoId` / `taxAuthorityRateSeqId`、`LeaseAcceptance.startTaxAcctgTransId` を追加した。商品画面に計上時期、契約画面に税率・計上時期・税務当局、検収結果に開始時消費税仕訳を表示する。
- demo：税務当局 `DEMO_TAXAUTH_JP`（JPN）、Company の `TaxAuthorityGlAccount`（224500）、税カテゴリ `DEMO_LEASE_TAX_STD`（`DEMO_LEASEPROD1` が所属）、10%（2019-10-01〜）。
- テスト：税務当局 `TEST_TAXAUTH_JP` と GL 割当。税カテゴリと税率行はテストごとに作る（日付は当日基準）。

### 6.5 テスト（`LeaseTaxTests`）

| テスト | 内容 |
|--------|------|
| `testTaxAllocationRounding` | JPY 切り捨て（各回／一括の最終回調整）、USD 四捨五入、非課税商品の税率 0、税率行のない商品は `lease.tax.rate`・税務当局なし |
| `testTaxRateFixedAtLeaseStart` | 1 か月前に 8% → 12% に改定：改定前開始の契約は見積・契約・全回とも 8%、改定後に期日の来た回も 8% で請求、`ITM_SALES_TAX`（税務当局つき）と GL（貸 224500＝税額、借 売掛金＝請求額、貸借一致）。改定後開始の契約は 12% |
| `testLumpSumTaxAtStart` | 開始時一括：開始時仕訳（借 128800 ／ 貸 224500＝総額）、各回の税の合計＝総額、請求は `LEASE_CONS_TAX` で 224500 を再計上せず 128800 を回収（合計＝総額）、再検収で同じ仕訳 |

### 6.6 検証

| 検証項目 | 結果 |
|----------|------|
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 列追加（`LEASE_AGREEMENT` 4 列、`LEASE_ACCEPTANCE` 1 列、`FINANCIAL_PRODUCT_LEASE` 1 列）、`FinanceLeaseTypeData.xml` 78 件 |
| `gradlew "ofbiz --test component=finance"` | tests=107 / errors=0 / failures=0 |
| demo 投入 | `FinanceLeaseDemoData.xml` 42 件、BUILD SUCCESSFUL |
| 画面 | 未確認 |

### 6.7 要確認・制約

- **計上時期の既定値**：要件 1.4 の確認（経理部門・税理士）が済んでいないため、既定は L4 と同じ「各回」とした。税法上の原則（引渡時の一括）を既定にする場合は `lease.tax.timing=LEASE_TAX_AT_START` に変える。
- **開始時一括の申告データ**：一括の税額は GL 仕訳だけで、core の税明細（`ITM_SALES_TAX`）を持たないため、core の税集計画面には出ない。
- **税込経理・軽減税率・インボイス制度の記載事項**は対象外。
- **既存契約**：L5 以前に検収した契約（開発 DB のデモ契約 `10010` 等）は計上時期・税務当局が空で、各回・税務当局なし（L4 の挙動）で請求される。

---

## 7. STEP L6 — 延滞・督促

延滞判定・区分・遅延損害金・督促・償却は共通層の `FinanceDelinquencyServices`（`SPEC_COMMON.md`）をそのまま使い、リース固有の部分を `LeaseContractAdapter` に実装した。延滞関連レコード（`LoanDelinquency` / `LoanLateFeeAccrual` / `LoanDunningNotice` / `LoanDelinquencyAction`）は `financeContractId` だけを持ち、`loanAgreementId` は空。

### 7.1 延滞の算定（`LeaseContractAdapter.computeDelinquency`）

- 現在の revision の確定リース料表のうち、期日が判定日以前で、請求書が PAID / WRITEOFF / CANCELLED でない回（未請求の回を含む）を延滞とする。延滞額は請求書の未消込額（未請求なら リース料＋消費税）。
- 入金は消費税 → 利息相当額 → 元本回収額の順に充当する（L4）ので、未払額は **元本回収額 → 利息相当額 → 消費税** の順に残る。これを延滞元本・延滞利息（利息相当額）・延滞消費税に分ける。
- 延滞日数は最も古い延滞回の期日からの日数。区分は商品の `FinancialProductDelinquency`（ローンと同じ）。
- `findOpenInvoiceIds`：未決済のリース請求書。償却時に `INVOICE_WRITEOFF` にする。

### 7.2 共通層の変更

| 変更 | 内容 |
|------|------|
| `DelinquencyComputation` | 延滞消費税（`overdueTax`）と遅延損害金の計算基礎（`lateFeeBase`、既定は延滞合計）を追加。延滞合計＝元本＋利息＋消費税。ローンは従来のコンストラクタのままで値は変わらない |
| `FinanceContractAdapter.getWriteOffCreditAccountType` | 償却時の貸方科目タイプを構成要素（元本／利息／消費税）ごとに返す default メソッド。既定（ローン）は元本・消費税＝売掛金、利息＝未収利息。リースはすべて売掛金（請求書で売掛金に計上済みのため） |
| `runLateFeeAccrual` | 計算基礎を `getLateFeeBase()` にした。`LoanLateFeeAccrual` に `overdueTax` / `lateFeeBaseAmount`、結果に同名の出力を追加 |
| 償却 | 構成要素ごとに「借 貸倒損失（`WRITEOFF`）／ 貸 アダプタの科目」を計上（消費税の償却を追加） |
| `LoanDelinquency.overdueTaxAmount` | 延滞消費税を記録 |
| `services_step6.xml` | `runLoanDelinquencyBatch` / `runLateFeeAccrual(Internal)` / `createLoanDunningNotice` に `leaseAgreementId` を追加（`FinanceContractAdapters.resolveContractId` が LEASE アダプタで解決） |
| `FindLoanDelinquency` | 一覧に `financeContractId`（`ViewFinanceContract` 経由で各層の契約画面へ）を追加。リースの行も表示される |

### 7.3 遅延損害金

- 年率は商品（`FinancialProduct.lateFeeAnnualRate`）、日割り・端数・GL（借 未収利息 121800 ／ 貸 遅延損害金収益 810100）はローンと同じ。
- 計算基礎は `finance.properties` の `lease.latefee.base`（SystemProperty で上書き可）：`TAX_INCLUDED`（既定、延滞リース料＋消費税）／ `TAX_EXCLUDED`（延滞リース料のみ）。

### 7.4 督促状・画面

- 督促状 PDF（`PrintLeaseDunningNotice.pdf`、`PrepareLeaseDunningNotice.groovy`）はローンの `DunningNotice.jrxml` を共用し、見出し・項目をリース用の文言（リース料お支払いのご請求、延滞リース料（税抜）・延滞消費税、明細は回・期日・未払額・リース料・消費税）で渡す。貸手名は契約の貸手組織の名称。
- `ManageLeaseAgreement`（検収後）に、延滞の判定結果、遅延損害金の計上（ACTIVE 中）と一覧、督促状の記録・PDF、償却・法的手続きの一覧を追加した。
- 顧客単位の照会（`FinanceCustomerSummary`）はローン・リースの延滞・督促を合わせて表示する（L0 の画面のまま）。
- demo：`DEMO_LEASEPROD1` に延滞区分 D1〜D4（償却・法的手続きは D4）を追加した。

### 7.5 テスト（`LeaseDelinquencyTests`。テストごとに USD のリース商品（遅延損害金 14.6%、区分 D1〜D4）と新しい借手）

| テスト | 内容 |
|--------|------|
| `testLeaseDelinquencyAndLateFee` | 45 日延滞・一部入金：D2、延滞日数 45、元本・利息相当額・消費税の分割と合計、同日再実行で 0 件、`loanAgreementId` 空、遅延損害金（計算基礎＝税込延滞額、GL 121800 ／ 810100、再実行で作成なし）、`TAX_EXCLUDED` で計算基礎から消費税が除かれること、全額入金済みの契約は延滞なし |
| `testLeaseWriteOffAndLegalAction` | 100 日延滞（D4）：償却記録（金額＝未決済請求額）、法的手続き記録、全請求書 WRITEOFF、償却仕訳 3 件（元本・利息・消費税）の貸方合計＝未決済額（売掛金 120000）、償却後は延滞なし |
| `testCustomerSummaryAcrossLoanAndLease` | 同一顧客のローン（45 日延滞）とリース（20 日延滞、D1）：契約指定のバッチ、`leaseAgreementId` での督促記録、顧客照会に両方の契約・延滞・督促、与信額＝両契約の元本合計 |

ローンの延滞テスト（`LoanDelinquencyTests`）を含む既存テストは全件成功した。

### 7.6 検証

| 検証項目 | 結果 |
|----------|------|
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 列追加（`LOAN_DELINQUENCY.OVERDUE_TAX_AMOUNT`、`LOAN_LATE_FEE_ACCRUAL.OVERDUE_TAX` / `LATE_FEE_BASE_AMOUNT`） |
| `gradlew "ofbiz --test component=finance"` | tests=110 / errors=0 / failures=0 |
| demo 投入 | `FinanceLeaseDemoData.xml` 46 件、BUILD SUCCESSFUL |
| 督促状 PDF・画面 | 未確認（OFBiz を起動しての確認は行っていない） |

### 7.7 要確認・制約

- **遅延損害金の計算基礎の既定**：税込（`TAX_INCLUDED`）とした。契約書の約定に合わせて経理・法務で確認する。遅延損害金は請求書を作らず GL 計上だけ（ローン STEP 6 と同じ）のため、入金時の遅延損害金の先行充当は行わない。
- **償却**：延滞分（期日到来分）の売掛金だけを償却する。未経過分のリース投資資産・繰延リース利益・物件の扱い（契約の終了）は対象外（中途解約 L14 で扱う）。償却時の消費税（貸倒れに係る税額控除）は GL 上は売掛金の償却に含めただけで、仮受消費税の調整は行わない（要確認）。
- **未請求の延滞回**：期日到来・未請求の回も延滞額に含める（ローンと同じ）。償却仕訳は売掛金を貸方にするため、月次バッチで請求してから延滞判定する運用とする。
- **一意索引**：延滞関連エンティティの一意索引は `loanAgreementId` を含む（STEP 6）。リースの行は `loanAgreementId` が空なので、NULL を重複とみなさない Derby では同日・同月の複数リースで衝突しない（テストで確認）。NULL を重複とみなす DB（SQL Server 等）では衝突するため、その DB を使う場合は索引を `financeContractId` ベースに見直す必要がある。
- **テストの前提**：遅延損害金・償却の GL 科目（810100 / 517000）は `FinanceStep6DemoData.xml` の割当を使う（ローンの延滞テストと同じ。テスト用 DB に demo が投入されていること）。

---

## 8. STEP L7 — 期間満了処理（返還・処分・所有権移転）と作業管理

実装：共通層 `FinanceWorkEffortHelper`（作業管理）、リース層 `LeaseExpiryServices`。

### 8.1 作業管理（共通層、`REQUIREMENTS_LEASE.md` 3.5）

| 項目 | 内容 |
|------|------|
| `FinanceContractWorkEffort`（`entitymodel_common.xml`） | `financeContractId` + `workEffortId`（PK）、作業種別 `financeWorkTypeEnumId`、`triggerKey`、作成日。契約＋作業種別＋きっかけで一意 |
| 作業種別（seed `FINANCE_WORK_TYPE`） | `FINWE_EXPIRY_NOTICE` 満了通知 / `FINWE_ASSET_RETURN` 返還引取り / `FINWE_ASSET_DISPOSAL` 物件処分 / `FINWE_OWN_TRANSFER` 所有権移転 / `FINWE_OTHER`（ID は 20 文字以内） |
| `ensureTask` | core `createWorkEffort`（`TASK`、`CAL_NEEDS_ACTION`、予定開始・期限）を呼び、担当者（`assignPartyToWorkEffort`、`CAL_OWNER`）と物件（`createWorkEffortFixedAssetAssign`）を任意で割り当てる。既存なら作らない（冪等） |
| `completeTask` | `updateWorkEffort` で `CAL_COMPLETED` と完了日を設定する。タスクがない・完了済みなら何もしない |
| `createFinanceContractTask`（サービス） | ローン・リースを問わず契約にタスクを作る（手動タスク。`triggerKey` 省略時は毎回新規） |

タスクの完了は業務サービスの結果として行い、タスクを完了にしても契約・物件の状態は変わらない（テストで確認）。タスクの一覧・編集は workeffort アプリ（`/workeffort/control/EditWorkEffort`）を使う。

### 8.2 データモデル（リース層）

| エンティティ | 内容 |
|--------------|------|
| `LeaseExpiryNotice` | 満了通知（契約ごとに 1 件）：通知日、対象のリース終了日、備考 |
| `LeaseAssetSettlement` | 物件ごとの満了時の処理：残価（契約の残価を取得価額比で按分、最終物件で端数調整）、返還日・備考、譲渡日、処分方法（`LEASE_DISPOSAL_TYPE`：売却／廃棄）・処分日・売却先・処分価額・処分損益・仕訳 |
| `LeaseAgreement.completedDate` | 完了日 |

seed：`LEASEAGR_ACTIVE → LEASEAGR_COMPLETED`、物件状態 `LSAST_DISPOSED`、GL 科目タイプ `LEASE_DISPOSAL_RECV`（128900 未収入金（物件売却））・`LEASE_DISPOSAL_GAIN`（403700 物件処分益）・`LEASE_DISPOSAL_LOSS`（503700 物件処分損）。Company への割当は demo・テストデータ。`lease.expiry.notice.months`（既定 3）を `finance.properties` に追加した。

### 8.3 サービス（`services_lease.xml`）

| サービス | 内容 |
|----------|------|
| `runLeaseExpiryBatch` | N か月以内に終了する ACTIVE 契約に満了通知タスク（きっかけ＝終了日）。終了日到来後は、所有権移転外なら物件ごとに返還引取りタスク（物件を割当）、所有権移転なら全回請求・入金済みのとき所有権移転タスク。再実行で重複しない |
| `issueLeaseExpiryNotice` | 満了通知を記録（契約ごとに 1 件、再実行は既存を返す）し、満了通知タスクを完了にする |
| `returnLeaseAsset` | 所有権移転外のみ。**リース終了日より前は拒否**。物件を `LSAST_RETURNED` にし、返還タスクを完了、処分タスクを作成。全物件返還・全回請求済み・請求書が全て決済済みなら契約を完了する |
| `disposeLeaseAsset` | 返還済みの物件の売却／廃棄（廃棄は処分価額 0）。残価を精算し（借 未収入金 ／ 貸 リース投資資産、差額を処分益／処分損）、物件を `LSAST_DISPOSED`、`FixedAsset.actualEndOfLife` を設定、処分タスクを完了。契約の完了後も可 |
| `transferLeaseOwnership` | 所有権移転リースのみ。**全回請求・入金済み、譲渡日が最終期日以降**でなければ拒否。譲渡価額（既定＝残価）で残価を精算、`FixedAsset.partyId` を借手に変更、物件を `LSAST_TRANSFERRED`、所有権移転タスクを完了し、契約を完了する |
| `completeLeaseAgreement` | 返還後に入金が揃った場合などの明示的な完了。条件は上と同じ。所有権移転外は終了日前を拒否 |

- 完了（`LEASEAGR_COMPLETED`、`FinanceContract` も `FINCTR_COMPLETED`）の前に、繰延リース利益をリース終了日まで戻し入れる（累計＝開始時の繰延リース利益）。`setLeaseAgreementStatus` からの完了は拒否する。
- 会計：開始時にリース投資資産／リース債権へ計上した残価は、処分・譲渡の精算で消える（開始・請求・精算を通して投資資産・債権の残高は 0。テストで確認）。

### 8.4 画面

- メニュー「満了予定一覧」（`FindLeaseExpiry`）：N か月以内に終了する ACTIVE 契約（借手・区分・終了日・満了通知日・未完了タスク数）と満了タスク作成（対象月数・担当者）。
- `ManageLeaseAgreement`：物件一覧に「返還記録」、満了処理欄（満了通知、所有権移転、処分記録、契約完了、返還・処分・譲渡の一覧）、タスク一覧（作業種別・タスク名（workeffort へのリンク）・状態・期限・完了日）。

### 8.5 テスト（`LeaseExpiryTests`。テストごとに USD のリース商品（12 か月、料率 0.09、残価率あり）と借手。開始は 14 か月前）

| テスト | 内容 |
|--------|------|
| `testReturnDisposalAndCompletion` | 移転外・残価 10%：状態サービスでの完了拒否、終了日前の返還拒否、移転外の譲渡拒否、満了予定一覧、タスク作成（物件・担当者の割当）と再実行で重複なし、満了通知でタスク完了・契約は ACTIVE のまま、タスクを手で完了にしても状態不変、未入金のまま返還→契約は未完了・処分タスク作成・明示完了は拒否、入金後に完了（共通状態、戻入累計＝繰延リース利益）、廃棄で処分価額ありを拒否、売却（処分益 30,000、GL 128900 ／ 128500 ／ 403700）、FixedAsset の終了日、処分タスク完了、二重処分の拒否、投資資産の残高 0 |
| `testOwnershipTransfer` | 移転・残価 5%：返還の拒否、未入金での譲渡拒否と移転タスクなし、入金後に移転タスク、譲渡で契約完了・タスク完了・物件 TRANSFERRED・FixedAsset の所有者＝借手・処分損益 0、リース債権の残高 0、完了後の再譲渡拒否 |
| `testTaskOnLoanContract` | ローン契約へのタスク（冪等、担当者割当、契約状態は不変）、存在しない契約の拒否 |

### 8.6 検証

| 検証項目 | 結果 |
|----------|------|
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 3 テーブル（`FINANCE_CONTRACT_WORK_EFFORT` / `LEASE_ASSET_SETTLEMENT` / `LEASE_EXPIRY_NOTICE`）、`LEASE_AGREEMENT.COMPLETED_DATE` |
| `gradlew "ofbiz --test component=finance"` | tests=119 / errors=0 / failures=0（所要 22.6 分） |
| demo 投入 | BUILD SUCCESSFUL |
| 画面 | 未確認 |

### 8.7 要確認・制約

- **満了通知書 PDF** は L8（帳票）で作る。L7 は通知の記録だけ（L3 の物件受領書と同じ扱い）。
- **処分代金の回収**：売却・譲渡代金は未収入金（128900）に計上するだけで、請求書・入金は扱わない。売却時の消費税も対象外。
- **所有権移転タスクの作成**は満了バッチで行う（最終回の入金時には作らない）。
- **満了バッチの対象**は DB 内の全 ACTIVE 契約。テスト DB には過去のテスト実行の契約が残るため、テスト時間が実行のたびに伸びる（今回は全体で 22.6 分、前回 L6 は 7.4 分。ローンのテストも同様に遅くなっており、L7 固有の処理ではなく DB の肥大が主因と見られる）。運用メモ参照。
---

## 9. STEP L8 — 帳票・照会

実装：`LeaseInquiryHelper`（照会・帳票データ。読み取りのみ。受領書の発行日の記録だけ更新する）。

### 9.1 帳票

| 帳票 | 出力 | 内容 |
|------|------|------|
| リース見積書・リース契約書 | `PrintLeaseQuote.pdf` / `PrintLeaseAgreement.pdf`（L2） | 変更なし |
| 物件受領書（検収書） | `PrintLeaseDocument.pdf?documentType=ACCEPTANCE` | 検収済みの契約。検収日・検収者・物件明細（番号・名称・数量・固定資産・取得価額）。初回出力で `LeaseAcceptance.receiptPrintedDate` を記録し、発行日として表示（再出力しても変えない） |
| 満了通知書 | `PrintLeaseDocument.pdf?documentType=EXPIRY` | 満了通知を記録した契約（L7 の `issueLeaseExpiryNotice`）。開始日・終了日・物件明細。本文は所有権移転外（返還のご案内）／移転（所有権移転のご案内）で切り替える |

- 受領書と満了通知書は 1 つの帳票定義（`LeaseDocument.jrxml`、`PrepareLeaseDocument.groovy`）で共用する。CJK フォントの設定はほかの帳票と同じ。
- `ManageLeaseAgreement` のボタン欄に、検収後は物件受領書、満了通知の記録後は満了通知書の PDF リンクを表示する。

### 9.2 照会画面（メニュー「リース照会」、タブで切替）

| 画面 | 内容 |
|------|------|
| 契約別リース料回収状況（`LeaseCollectionStatus`） | ACTIVE / COMPLETED の契約（借手・状態で絞込）：リース料総額、請求回数、請求額、消込額、未入金額、未請求リース料、未回収元本、延滞日数（ACTIVE のみ） |
| 未回収元本残高一覧（`LeaseOutstandingPrincipal`） | ACTIVE 契約を借手×通貨で集計：契約数、取得価額、未回収元本、未入金額、未請求リース料、繰延リース利益残高。借手から顧客照会へリンク |
| 物件台帳（`LeaseAssetRegister`） | 契約物件（状態・借手・物件種別で絞込）：物件名・種別・固定資産・状態・借手・所有者（`FixedAsset.partyId`）・取得価額・開始日・終了日・返還日・譲渡日・処分日 |

金額はすべて L4 の回収状況（`computeBalance`、有効な消込を正本）から都度計算する。

### 9.3 月次バッチのトランザクション分割（L4 の変更）

L8 のテスト中に、DB の肥大で月次バッチが 1 トランザクションの既定タイムアウト（60 秒）を超えた。対策として、`runLeaseMonthlyAccountingBatch` は契約ごとに内部サービス `runLeaseAgreementMonthlyAccounting`（新規トランザクション、タイムアウト 1800 秒）を呼ぶように変えた。1 契約の失敗・長時間処理がほかの契約を巻き戻さない。`runLeaseMonthlyAccountingBatch` / `runLeaseExpiryBatch` のタイムアウトも 1800 秒にした。

### 9.4 テスト（`LeaseInquiryTests`）

| テスト | 内容 |
|--------|------|
| `testLeaseInquiries` | 同一借手の 2 契約（1 契約は第 1 回入金済み）：回収状況（請求回数、未回収元本、未入金額、延滞日数）、状態での絞込、借手別の集計（契約数・取得価額・未回収元本の合計）、物件台帳（状態・所有者・固定資産、状態・種別での絞込） |
| `testLeaseDocuments` | 物件受領書のデータ（表題・契約番号・借手・検収日・物件明細）と **JRXML のコンパイル・PDF 生成**（`%PDF-`）、受領書の発行日の記録（初回のみ）、満了通知の記録前は出力不可、満了通知書のデータ（本文・終了日）と PDF、不正な種別・契約の拒否 |

PDF の生成は jasperreports プラグインのクラスを実行時に読み込んで行う（finance プラグインは JasperReports にコンパイル時依存を持たない方針を維持）。

### 9.5 検証

| 検証項目 | 結果 |
|----------|------|
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | L8 で entity・seed の追加なし |
| `gradlew "ofbiz --test component=finance"` | tests=121 / errors=0 / failures=0（9.2 分。開発 DB 再構築後） |
| 画面・PDF のブラウザ表示 | 未確認（PDF はテストで生成を確認） |

### 9.6 制約

- 照会は画面上で全件を計算する（ページ表示 50 件）。契約数が多い場合は集計テーブルやバッチでの事前計算を検討する。
- 帳票の貸手住所は表示しない（貸手名のみ）。
---

## 10. STEP L9 — リース料の構成要素（維持管理費用相当額）

元本・利息相当額とは別の費用部分（固定資産税相当額・保険料・保守料 等）を、リース料計算・見積／確定リース料表に持たせる。
構成要素ごとに消費税区分（4 種）と収益計上の扱い（第1法の売上高に含める／区分して別に計上する）を選べる。
**元本・利息相当額の計算（`LeaseCalculator`）は変更しない**。構成要素は加算的・独立で、既定では区分して別に計上する。
既存の見積・契約は不変（構成要素のない従来どおりの挙動）で、新しい見積から適用する。

実装：`LeaseComponentCalculator`（構成要素の配分、エンティティ非依存）。見積（`LeaseQuoteServices.groovy`）・検収（`LeaseAcquisitionServices`）・
請求／回収状況（`LeaseAccountingServices`）・延滞（`LeaseContractAdapter`）に構成要素を追加した。

### 10.1 スコープと決定事項

- **収益計上の扱い**：要件表は固定資産税相当額を「区分する」で確定、要件本文は「含める／区分」を選択式。
  **既定＝区分（`LSCMP_REV_SEPARATE`）、構成要素ごとに `含める（LSCMP_REV_INCLUDED）` を選べる形**とした（保守料 L11 の「区分して別に計上」に備える）。
- **消費税区分 4 種**（`LEASE_TAX_CLASS`）：課税（`LSTAX_TAXABLE`）・非課税（`LSTAX_EXEMPT`）・不課税（`LSTAX_NONTAX`）・免税（`LSTAX_ZERO`）。
  消費税を課すのは**課税のみ**（金額 × 契約の固定税率）。非課税・不課税・免税は税額 0（免税は税率 0% の課税として税額 0 とする）。
  固定資産税相当額は**課税**（要件の決定）。
- **構成要素の金額**：当面は**月額均等**（毎回同額）とした。回ごとの減額・調整（定期傭船の休航 等、L18）は後続で追加する。
- **土台（契約×取引先×役割×持分率）は本 STEP では実装しない**（下記 10.6 に記録）。

### 10.2 データモデル（`entitymodel_lease.xml` に追加）

| エンティティ | 主キー | 内容 |
|--------------|--------|------|
| `LeaseQuoteComponent` | leaseQuoteId + componentSeqId | 見積の構成要素定義：種別（`LEASE_COMPONENT_TYPE`）、説明、月額、消費税区分、収益計上の扱い、合計額・消費税合計（月額 × 期間） |
| `LeasePaymentScheduleComp` | leaseQuoteId + installmentNumber + componentSeqId | 見積リース料表の回ごとの構成要素額・消費税（種別・区分・収益計上の扱いの写し） |
| `LeaseAgreementComponent` | leaseAgreementId + componentSeqId | 契約の構成要素定義（見積のスナップショット） |
| `LeaseAgrPaySchedComp` | leaseAgreementId + scheduleRevisionNumber + installmentNumber + componentSeqId | 確定リース料表の回ごとの構成要素額・消費税 |
| `LeaseInstallmentInvoice`（追加列） | — | `componentAmount`（請求した構成要素合計・税抜）、`componentTaxAmount`（構成要素の消費税合計）。`billedAmount` ＝ リース料＋消費税＋構成要素＋構成要素消費税 |

### 10.3 seed（`FinanceLeaseTypeData.xml` に追加）

| 種類 | 値 |
|------|----|
| `LEASE_COMPONENT_TYPE` | `LSCMP_PROP_TAX`（固定資産税相当額）/ `LSCMP_INSURANCE`（保険料）/ `LSCMP_MAINTENANCE`（保守料）/ `LSCMP_OTHER`（その他） |
| `LEASE_TAX_CLASS` | `LSTAX_TAXABLE`（課税）/ `LSTAX_EXEMPT`（非課税）/ `LSTAX_NONTAX`（不課税）/ `LSTAX_ZERO`（免税） |
| `LEASE_CMP_REVENUE` | `LSCMP_REV_SEPARATE`（区分、既定）/ `LSCMP_REV_INCLUDED`（含める） |
| 請求明細種別 | `LEASE_COMPONENT`（構成要素）/ `LEASE_COMP_TAX`（構成要素の消費税）と `SALES_INVOICE` への `InvoiceItemTypeMap` |
| GL 科目タイプ | `LEASE_COMP_REVENUE`（403800 リース構成要素収益、収益・`SALES_ACCOUNT` 配下） |

Company への科目割当（403800）は demo・テストデータに追加した。

### 10.4 計算・保存・請求

- **計算（`LeaseComponentCalculator`）**：構成要素ごとに月額均等で配分する。各回の消費税＝課税のとき 金額 × 税率（通貨別丸め）、それ以外は 0。合計額・消費税合計を返す。月額が負・期間が 0 以下は拒否。
- **見積（`createLeaseQuote`、`components` 引数）**：構成要素の列挙値（種別・税区分・収益計上の扱い）と月額（0 以上）を検証し、`LeaseQuoteComponent` と回ごとの `LeasePaymentScheduleComp` を作る。見積の税率は L5 と同じく開始予定日で解決する。**リース料・利息相当額は構成要素の有無で変わらない**。
- **契約（`createLeaseAgreement`）**：構成要素の定義と回ごとの行を revision 1 にスナップショットする。
- **検収（`acceptLeaseAgreement`）**：確定リース料表の新 revision を作り直すとき、構成要素も新 revision に作り直し、**課税の構成要素の消費税を開始日の固定税率で付け直す**（リース料の消費税の固定と同じ扱い。L5）。定義の消費税合計も再計算する。
- **請求（`createLeaseInstallmentInvoice`）**：回の構成要素を請求明細に追加する。
  - **区分（`LSCMP_REV_SEPARATE`）**：明細 `LEASE_COMPONENT`（`overrideGlAccountId` ＝ `LEASE_COMP_REVENUE` 403800）で**請求時に収益計上**する。課税分は `LEASE_COMP_TAX`（税務当局があれば core `ITM_SALES_TAX`、なければ仮受消費税 224500）。
  - **含める（`LSCMP_REV_INCLUDED`）**：明細 `LEASE_COMPONENT` を**リース料と同じ貸方科目（リース投資資産／リース債権）**に計上する（開始時に第1法の売上高へ含めた前提。新たな売上は立てない）。
  - `LeaseInstallmentInvoice` に `componentAmount` / `componentTaxAmount` を記録する。貸借は core の売上請求仕訳で自動的に一致する（借 売掛金＝請求総額）。

| 借方 | 貸方 | 金額 |
|------|------|------|
| 売掛金（core） | リース投資資産／リース債権 | リース料（＋含める構成要素） |
| 売掛金（core） | リース構成要素収益（403800） | 区分の構成要素 |
| 売掛金（core） | 仮受消費税（224500）／ core 税当局科目 | リース料の消費税 ＋ 課税の区分構成要素の消費税 |

### 10.5 回収状況・延滞

- **回収状況（`computeBalance`）**：請求額・消込額に構成要素を含める（`billedComponentAmount` / `billedComponentTaxAmount` / `collectedComponentAmount` を追加）。1 請求書の消込は **消費税（リース＋構成要素）→ 構成要素 → 利息相当額 → 元本回収額** の順に充当する。**未回収元本は構成要素の影響を受けない**（請求しただけでも減らない点は L4 と同じ）。
- **延滞（`LeaseContractAdapter`）**：未払額は **元本 → 利息相当額 → 構成要素 → 消費税** の順に残す。構成要素の未払は延滞額の消費税バケットにまとめる（償却時はすべて売掛金の貸方。アダプタの `getWriteOffCreditAccountType`）。**遅延損害金の計算基礎には構成要素を含めない**（基礎はリース料＝元本＋利息相当額、`lease.latefee.base` で消費税の算入を切替。構成要素は常に基礎外）。未請求の回は確定リース料表の構成要素額を見込みに含める。

### 10.6 土台（契約×取引先×役割、契約の親子）の見送り（記録）

要件「後続 STEP の設計上の考慮事項 1.（土台）」で、L9 の前提作業として共通層に **`FinanceContractParty`（契約×取引先×役割×持分率）** と
**`FinanceContract` の親子関係** を置くことが挙げられている（L16〜L20：協調・転貸・フリート・ベンダープログラムで作り直しが起きないため。遅くとも L10 の前）。

- **本 STEP（L9）では実装しない**。L9 の費用構成要素は 1 契約＝1 借手・1 貸手の現行モデルの上で完結し、土台がなくても業務の挙動は変わらないため。
- 要件の記述どおり「遅くとも L10 の前」に別途着手する。着手時は、既存契約を「借手 100%・貸手（自社）100%」として登録し、借手への請求スケジュールは 1 本のまま、取引先・持分に応じた分配・支払側を**追加**する形（L1〜L8 の請求・入金を作り直さない）とする。ローンの保証人も将来この形へ寄せる。
- 影響範囲（移行時に見直す箇所）：`LeaseAgreement.lesseePartyId` / `organizationPartyId`、`FinanceContract.customerPartyId`、請求書の相手、`FixedAsset.partyId`。

### 10.7 画面

- `ViewLeaseQuote`：構成要素があるとき「リース料の構成要素」グリッド（`ListLeaseQuoteComponents`、読み取り専用）を表示する。
- `ManageLeaseAgreement`：確定リース料表の下に「リース料の構成要素」グリッド（`ListLeaseAgreementComponents`、読み取り専用）を表示する。
- 構成要素の入力は当面サービス API（`createLeaseQuote` の `components`）で行う。見積作成フォームでの構成要素入力 UI は後続で追加する。

### 10.8 テスト（`LeaseComponentTests`。USD 商品 `TEST_LEASEPROD_USD`、テストごとに新しい借手）

| テスト | 内容 |
|--------|------|
| `testComponentCalculator` | 月額均等の配分、課税＝金額 × 税率、非課税・不課税・免税＝税額 0、合計、負の月額の拒否 |
| `testQuoteAndAgreementComponents` | 構成要素つき見積でリース料・利息相当額が不変、定義（合計・消費税合計）と回ごとの行の保存（2 要素 × 60 回）、契約へのスナップショット、検収で新 revision へ作り直し課税分の消費税を固定税率で付け直し（revision 2）、不正な列挙値の拒否 |
| `testBillingSeparateAndIncludedComponents` | 区分（固定資産税相当額）＝403800 収益＋224500 消費税、含める（保守料・非課税）＝128500 に加算、貸借一致・売上（403500）なし、`componentAmount` / `componentTaxAmount` の記録、全額入金で構成要素・消費税を充当し**元本は構成要素の影響を受けない** |

### 10.9 検証

| 検証項目 | 結果 |
|----------|------|
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 4 テーブル（`LEASE_QUOTE_COMPONENT` / `LEASE_PAYMENT_SCHEDULE_COMP` / `LEASE_AGREEMENT_COMPONENT` / `LEASE_AGR_PAY_SCHED_COMP`）、`LEASE_INSTALLMENT_INVOICE` に 2 列追加、構成要素・税区分・収益計上の列挙と GL 403800 |
| `gradlew "ofbiz --test component=finance"` | `runtime/logs/test-results/financetests.xml`：tests=126 / errors=0 / failures=0（L9 の 3 件を含む） |
| demo 投入 | 403800 の Company 割当を追加（demo・テストデータ） |
| 画面 | 未確認（グリッドの定義のみ。OFBiz を起動しての確認は行っていない） |

### 10.10 要確認・制約

- **消費税区分の最終確認**：課税・非課税・不課税・免税の判断（固定資産税相当額＝課税 等）は税理士の最終確認を得る（要件の残課題）。L19 の自賠責・重量税等の法定費用の立替（不課税）扱いは L19 で判断する。
- **「含める」の開始仕訳**：区分（既定）は開始仕訳に影響しない。「含める」を使う場合、第1法の開始時の売上高・リース投資資産に構成要素相当を含める必要があるが、本 STEP の開始仕訳（`LeaseRevenueStrategy.AtStart`）は従来どおり取得価額ベースのままで、含める構成要素は請求時にリース投資資産の貸方へ回収する簡易な扱いとした。含める運用を本格的に使う場合は開始仕訳の見直しが必要（要確認）。
- **月額均等のみ**：回ごとの減額・調整、年次で変動する固定資産税相当額（L10 が供給）の反映は後続で追加する。L10 は本 STEP の構成要素（固定資産税相当額）に見込み額を供給する。
- **延滞の構成要素**：延滞額には構成要素を含める（消費税バケットに合算）が、遅延損害金の計算基礎には含めない。構成要素だけが延滞した場合の督促文言は未調整（既存のリース料督促の文言を流用）。

---

## 11. STEP L10 — 固定資産税（償却資産税）

リース会社所有の物件（**所有権移転外**ファイナンスリース。所有権移転は借手が申告するため対象外）について、
毎年 1 月 1 日時点の評価額を物件ごとに計算し、所在の市町村ごとに申告データ（免税点 150 万円の判定）を作り、
納付を記録して費用計上する。会計上の減価償却（L12）とは独立した税務計算。見積時の見込み額を L9 の
固定資産税相当額（構成要素）に供給する。

実装：`PropertyTaxValuation`（評価額、エンティティ非依存）、`LeasePropertyTaxServices`（税率解決・見込み供給・申告生成・納付）。

### 11.1 スコープ（A）

- L10 の業務ロジック（評価・税率・見込み・申告・納付）を実装し、テストで検証する。
- **市区町村 Geo は seed にデモ／テスト用の数件だけ**入れる（千代田区 `JP-131016`、横浜市 `JP-141003`）。全国約 1,900 件の全件投入は別途データ整備タスクとする（11.7 に手順）。

### 11.2 評価額（`PropertyTaxValuation`、Java・エンティティ非依存）

- 減価率 `r` は耐用年数 `n` から旧定率法相当で導出：`r = 1 − 0.1^(1/n)`（耐用年数末で残存率 10%）。明示指定も可。
- 評価額：取得初年度は半年分 `取得価額 ×(1 − r/2)`、以降の各年は前年 `×(1 − r)`、**下限は取得価額の 5%**。
- 1 月 1 日基準：取得年の翌年 1 月 1 日に初めて課税台帳に載る（`assessedValue(cost, r, 取得年, 課税年度)` は課税年度 ≤ 取得年なら 0）。

### 11.3 データモデル（`entitymodel_lease.xml` に追加）

| エンティティ | 主キー | 内容 |
|--------------|--------|------|
| `LeasePropertyTaxRate` | leasePropertyTaxRateId | 市町村税率（`municipalityGeoId`、`taxRate`、`standardRate` 標準税率フラグ、from/thru）。市町村の行がなければ標準税率を使う |
| `LeasePropertyTaxDecl` | leasePropertyTaxDeclId | 貸手組織 × 課税年度 × 市町村の申告：資産件数、評価額合計、課税標準額、免税点、免税点未満フラグ、税率、税額、状態（`LEASE_PTAX_STATUS`）。組織＋年度＋市町村で一意 |
| `LeasePropertyTaxDeclAsset` | declId + fixedAssetId | 申告に含めた物件（取得価額・取得年・耐用年数・減価率・評価額） |
| `LeasePropertyTaxPayment` | declId | 納付（納付日・金額・core Payment（任意）・仕訳）。申告ごとに 1 件 |
| `LeaseAgreementAsset`（追加列） | — | `usefulLifeYears`（耐用年数）、`municipalityGeoId`（物件所在の市町村） |

物件の所在：`LeaseAgreementAsset.municipalityGeoId` を優先し、空なら `FixedAsset.locatedAtFacilityId` →
`FacilityContactMech`（現在の POSTAL_ADDRESS）→ `PostalAddress.municipalityGeoId` の順で解決する。

### 11.4 seed・設定

| 種類 | 値 |
|------|----|
| `LEASE_PTAX_STATUS` | `LEASE_PTAX_GENERATED`（生成）→ `LEASE_PTAX_PAID`（納付済） |
| GL 科目タイプ | `LEASE_PTAX_EXPENSE`（601500 租税公課、費用）／ `LEASE_PTAX_PAYABLE`（224600 未払固定資産税、流動負債） |
| 市区町村 Geo | `JP-131016` 千代田区・`JP-141003` 横浜市（`geoCode`＝全国地方公共団体コード 6 桁、`REGIONS` で `JP-13`／`JP-14` に関連付け） |
| `LeasePropertyTaxRate` | `LPTR_STANDARD`（標準 1.4%）、`LPTR_YOKOHAMA`（横浜市 超過課税の例 1.5%） |
| `finance.properties` | `lease.propertytax.usefullife.years=6`、`lease.propertytax.exemption.threshold=1500000`、`lease.propertytax.standard.rate=0.014` |

Company への GL 割当（601500／224600）は demo・テストデータに追加した。

### 11.5 サービス

| サービス | 内容 |
|----------|------|
| `resolveLeasePropertyTaxEstimate` | 見積の見込み額：取得価額・期間・所在市町村の税率から、各課税年度の評価額（1/1）× 税率を期間平均して年額・月額を返す。**免税点は適用しない**（1 契約では判定できないため課税として見込む）。L9 の `createLeaseQuote` が、金額未指定・`municipalityGeoId` 指定の `LSCMP_PROP_TAX` 構成要素に対して自動で呼ぶ |
| `generateLeasePropertyTaxDeclarations` | 課税年度の 1/1 時点で、貸手組織所有（`FixedAsset.partyId`＝組織）の**所有権移転外**・ACTIVE/COMPLETED のリース物件を市町村ごとに集計。評価額合計に対し**免税点 150 万円**を市町村単位で判定し、免税点以上なら課税標準額（1000 円未満切捨）× 税率を税額（100 円未満切捨）とする。組織＋年度＋市町村で冪等。**所在が解決できない物件はスキップ**（`skippedAssetCount`。所在データが未整備でもバッチは止めない） |
| `recordLeasePropertyTaxPayment` | 申告の納付を記録し費用計上：**借 租税公課（601500）／ 貸 未払固定資産税（224600）**。申告ごとに冪等、申告を `LEASE_PTAX_PAID` にする。現金出金（買掛金の消込）は対象外 |

### 11.6 画面

- メニュー「固定資産税」（`FindLeasePropertyTax`）：申告データ生成フォーム（組織・課税年度）、申告一覧（市町村・評価額・課税標準・免税点未満・税率・税額・状態・納付、未納は「納付記録」リンク）。
- 入力（物件の所在・耐用年数、見積の構成要素）は当面サービス API 中心。見積の構成要素への見込み供給は L9 の `createLeaseQuote` に統合済み。

### 11.7 市区町村 Geo の全件投入（別タスクの手順）

全国約 1,900 件の市区町村を本番で使うには、総務省「全国地方公共団体コード」から次を生成して seed（または別リーダ）で投入する。

- `Geo`：`geoId`＝`JP-` ＋ 団体コード（6 桁、検査数字込み。20 文字以内に収まる）、`geoTypeId=MUNICIPALITY`、`geoName`＝市区町村名、`geoCode`＝団体コード。
- `GeoAssoc`：`geoId`＝都道府県（`JP-01`〜`JP-47`）、`geoIdTo`＝市区町村、`geoAssocTypeId=REGIONS`。
- 超過課税の市町村だけ `LeasePropertyTaxRate` に行を追加（既定は標準税率 1.4%）。
- 市町村合併等で団体コードが変わった場合の Geo 更新手順（旧コードの thru 設定、新コードの追加、`LeaseAgreementAsset.municipalityGeoId` の付け替え）を運用で定める（要確認）。

### 11.8 テスト（`LeasePropertyTaxTests`。USD 非移転商品 `TEST_LEASEPROD_USD`。免税点の集計を分離するためテストごとに専用の市町村 Geo＋税率を作る）

| テスト | 内容 |
|--------|------|
| `testValuationCalculation` | 初年度半年・以降各年・5% 下限、取得年以前は評価額 0・翌年から半年分、不正入力の拒否 |
| `testRateResolutionAndEstimate` | 標準税率 1.4%、市町村の超過課税の優先、税率が高いほど見込みが大きい、不正入力の拒否 |
| `testEstimateSuppliedToComponent` | 金額未指定・所在指定の `LSCMP_PROP_TAX` 構成要素が見込み額で自動設定され、`resolveLeasePropertyTaxEstimate` と一致 |
| `testDeclarationAndPayment` | 免税点以上の市町村は課税（課税標準 1000 円未満切捨・税額 100 円未満切捨）、免税点未満の市町村は税額 0・免税点未満フラグ、冪等、納付仕訳（借 601500 ／ 貸 224600、貸借一致、申告 PAID）、冪等納付、免税点未満の納付拒否 |

### 11.9 検証

| 検証項目 | 結果 |
|----------|------|
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 4 テーブル（`LEASE_PROPERTY_TAX_RATE` / `LEASE_PROPERTY_TAX_DECL` / `LEASE_PROPERTY_TAX_DECL_ASSET` / `LEASE_PROPERTY_TAX_PAYMENT`）、`LEASE_AGREEMENT_ASSET` に 2 列、状態・GL・Geo・税率の seed |
| `gradlew "ofbiz --test component=finance"` | `runtime/logs/test-results/financetests.xml`：tests=134 / errors=0 / failures=0（L10 の 4 件を含む） |
| demo 投入 | 601500／224600 の Company 割当を追加（demo・テストデータ） |
| 画面 | 未確認（フォーム・グリッドの定義のみ） |

### 11.10 要確認・制約

- **消費税**：固定資産税相当額は L9 で課税（区分の別収益）として扱う。固定資産税そのもの（本税）は不課税のため、`recordLeasePropertyTaxPayment` の費用計上に消費税は付さない。
- **評価額の方式**：旧定率法相当（`r = 1 − 0.1^(1/n)`、初年度半年、下限 5%）を自前計算とした。実際の償却資産課税は「減価残存率表」に基づくため、本番では耐用年数ごとの残存率表を `LeasePropertyTaxRate` と同様のテーブルで持たせる余地がある（要確認）。
- **課税は 1/1 の所在で決まる**：`LeaseAgreementAsset.municipalityGeoId`（または FixedAsset の設置場所）は現在値で、所在の履歴は持たない。リース期間中の移設で課税市町村・税率が変わる場合の差額（貸手負担／精算）は契約条件で定める（要確認）。
- **納付**：費用計上（借 租税公課 ／ 貸 未払固定資産税）までを行い、未払金の現金出金・消込は対象外。
- **所在未設定の物件**：申告生成でスキップする（`skippedAssetCount`）。本番では所在未設定を別途点検する運用が必要。
- **耐用年数**：`LeaseAgreementAsset.usefulLifeYears` → FixedAsset の取得日・耐用年数末 → `finance.properties` の既定（6 年）の順で解決する。

---

## 運用メモ

- **seed の投入**：L1 で `FinanceLeaseTypeData.xml` を追加した。既存環境では `gradlew "ofbiz --load-data readers=seed --load-data component=finance"` を実行してからテスト・画面操作を行う（未投入だとリース商品種別・申込状態がなく失敗する）。
- **demo**：`FinanceLeaseDemoData.xml`（事務機器ファイナンスリース・所有権移転外 `DEMO_LEASEPROD1`、借手 `DEMO_LESSEE1`、販売店 `DEMO_LS_SUPPLIER1`、受付中の申込 `DEMO_LEASEAPP1` と希望物件 2 件）。
- **L2 の画面確認で作成したデータ**：開発 DB に借手 DEMO_LESSEE1 のリース申込 10030、見積 10010、契約 10010（FinanceContract 10230、CONTRACTED）が残っている。demo の再投入では消えない。不要なら画面から契約を取り消す。
- **見積・契約の日付**：L2 の期日は開始予定日からの暫定値。リース開始日（検収日）で確定するのは L3。
- **外貨建てリースの換算（既知の問題・core）**：契約通貨と貸手組織の基準通貨が違う場合（demo の JPY 契約と、基準通貨 USD の `Company`）、finance 側の仕訳は `convertUom`（JPY→USD 0.006666666667）で換算する。一方、core の支払消込仕訳（`createAcctgTransAndEntriesForPaymentApplication`）は換算率を小数 4 桁（0.0067）に丸めるため、1,500,000 JPY が 10,050 USD で計上され、買掛金・未消込買掛金に 50 USD の差が残った（契約 `10010` の取引 `11042`）。各取引の貸借は一致している。契約通貨と基準通貨が同じ本番構成（JPY の貸手）では発生しない。core は変更していない。外貨建てリースを扱う場合は L4 以降で対応を検討する。
- **開発 DB の再構築（2026-10-13、L8）**：テスト時間の増加（L6 7.4 分 → L7 22.6 分 → L8 48 分）のため `gradlew cleanAll loadAll` で開発 DB を作り直した。L2・L3 の画面確認で作ったデータ（デモ契約 `10010` 等）は消えている。再構築後のテストは 9.2 分。テストを繰り返すと再び遅くなるため、目安として 20 分を超えたら再構築を検討する。
- **テスト時間の増加（L7 時点）**：テストは既定 DB を共有し、実行のたびに契約・請求・仕訳が残る。L7 のテストは全体で 22.6 分かかった（L6 では 7.4 分）。各サービスの処理時間が全体的に伸びており、DB の肥大が主因と見られる。短縮するには、開発用 DB を作り直す（`gradlew cleanAll loadAll` 等。開発 DB の全データが消えるため、実施前に確認すること）か、テスト専用の DB（別 delegator）を用意する。
