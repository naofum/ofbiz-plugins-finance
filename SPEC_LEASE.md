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
| L10b | 公租公課への一般化（税目・物件種別・所有持分、テーブル名の変更と移行。12 章） | 完了（tests=143 / errors=0 / failures=0） |
| L11 | メンテナンスリース（保守料の別収益計上、保守計画・予定・実績、保守原価。13 章） | 完了（tests=150 / errors=0 / failures=0） |
| L12 | オペレーティングリース（賃貸資産のリース期間定額法による減価償却、受取リース料の請求時計上。14 章） | 完了（tests=166 / errors=0 / failures=0） |
| L13 | 再リース（元の契約に再リース期間を追加、年額の前払請求と月次の収益按分、残存簿価の償却。15 章） | 完了（tests=174 / errors=0 / failures=0） |
| L14 | 中途解約（規定損害金＝未経過リース料の現在価値、リース投資資産の精算、物件の引揚。16 章） | 完了（tests=182 / errors=0 / failures=0） |
| L15 | 条件変更（期間・月額・計算利子率、確定リース料表の新 revision、投資資産と繰延利益の調整。17 章） | 完了（tests=186 / errors=0 / failures=0） |
| L15a | 誤操作の取消（検収・中途解約・条件変更。18 章。共通部品は `SPEC_COMMON.md` 11 章） | 完了（tests=192 / errors=0 / failures=0） |
| L15b | 祝日カレンダーを考慮した営業日調整（共通層。`SPEC_COMMON.md` 12 章。リースは見積・検収・条件変更の期日に適用） | 計画（未着手） |
| L15c | 前受リース料と保証金（19 章） | 計画（未着手） |
| L15d | 物件分割・契約分割（20 章） | 計画（未着手） |

前提：STEP L0（共通層の切り出し、`SPEC_COMMON.md`）の完了後に着手する。

---

## 1. リース層の構成

| 種別 | 配置 |
|------|------|
| Java | `org.apache.ofbiz.finance.lease`（L1：`LeaseProductHelper`、L2：`LeaseCalculator` / `LeaseContractAdapter`、L3：`LeaseAcquisitionServices` / `LeaseRevenueStrategy`、L4：`LeaseAccountingServices` / `LeaseProfitAllocator`、L5：`LeaseTaxHelper`、L9：`LeaseComponentCalculator`、L10：`PropertyTaxValuation`、L10b：`LeasePublicChargeCalculator` / `LeasePublicChargeServices`、L11：`LeaseComponentSettings` / `LeaseMaintenanceServices`、L12：`LeaseRevenueStrategy.Operating` / `LeaseDepreciationServices`、L13：`LeaseReLeaseServices`、L14：`LeaseTerminationServices`、L15：`LeaseModificationServices`） |
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

### 10.6 土台（契約×取引先×役割、契約の親子）

要件「後続 STEP の設計上の考慮事項 1.（土台）」の共通層 **`FinanceContractParty`（契約×取引先×役割×持分率）** と
**`FinanceContract` の親子関係** は、L9 では実装せず、L10 の後に **STEP L10a** として実施した（`SPEC_COMMON.md` 10 章）。

- L9 の費用構成要素は 1 契約＝1 借手・1 貸手の現行モデルの上で完結し、土台がなくても挙動は変わらないため L9 からは切り離した。
- L10a は追加のみ：新規契約は借手・貸手を 100% で自動登録し、既存契約は `backfillFinanceContractParties` で登録する。リースの請求・入金・延滞・固定資産税は引き続き `LeaseAgreement.lesseePartyId` / `organizationPartyId` を読む。
- 土台を読むように切り替える箇所（L16 以降）：`LeaseAgreement.lesseePartyId` / `organizationPartyId`、`FinanceContract.customerPartyId`、請求書の相手、`FixedAsset.partyId`、L10 の申告（物件の所有持分による按分）。

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
- **「含める」の開始仕訳**：区分（既定）は開始仕訳に影響しない。L11 以降、保守料は区分のみ（`LeaseComponentRevenueAccount.separateRequired`）。「含める」を使う場合、第1法の開始時の売上高・リース投資資産に構成要素相当を含める必要があるが、本 STEP の開始仕訳（`LeaseRevenueStrategy.AtStart`）は従来どおり取得価額ベースのままで、含める構成要素は請求時にリース投資資産の貸方へ回収する簡易な扱いとした。含める運用を本格的に使う場合は開始仕訳の見直しが必要（要確認）。
- **月額均等のみ**：回ごとの減額・調整、年次で変動する固定資産税相当額（L10 が供給）の反映は後続で追加する。L10 は本 STEP の構成要素（固定資産税相当額）に見込み額を供給する。
- **設計上の考慮事項 2.（`REQUIREMENTS_LEASE.md`）との差（2026-10-15 確認）**：消費税区分（免税を含む 4 種）と収益計上の扱い（含める／区分、L11 で種類ごとの収益科目）は対応済み。次の 3 点は未対応で、L18（船舶リース）の前に行う。
  1. 構成要素の分類（リース部分・サービス部分・公租公課・保険・調整）：種類（`LEASE_COMPONENT_TYPE`）はあるが、分類の区分と「調整（減額）」の種類がない。
  2. 構成要素ごとの請求の有無：すべての構成要素をリース料と一緒に請求する。請求しない構成要素（貸手負担等）の設定がない。
  3. 確定リース料表の回ごとの減額・調整（定期傭船の休航 等）：構成要素は月額均等のみで、回ごとの調整を記録できない。
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

> **L10b で置き換え済み**：本章のエンティティ名・サービス名・状態・GL 科目タイプ・`lease.propertytax.*` は 12 章（公租公課）の名前に変わった。計算規則（評価額・免税点・端数処理）は変えていない。

---

## 12. STEP L10b — 公租公課への一般化

L10 の固定資産税（償却資産税）を「公租公課」として一般化した（`REQUIREMENTS_LEASE.md`「後続 STEP の設計上の考慮事項」3.）。
税目と評価方法を物件種別ごとに切り替えられるようにし、物件の所有持分を扱う。償却資産税の計算結果は L10 と同じ。

### 12.1 設計

- **税目**（`LeasePublicChargeType`、seed）：計算方法、課税団体の単位、賦課期日、免税点、課税標準・税額の端数単位、既定税率、既定耐用年数、見込み額を入れる構成要素、費用・未払の GL 科目タイプを持つ。L10 で `finance.properties` に置いていた値は税目の行に移した。
- **計算方法**（`LeasePublicChargeCalculator`、`LEASE_PCHG_CALC`、エンティティ非依存）：`LeaseRevenueStrategy` と同じく `REGISTRY` で差し替える。
  - `LSPC_CALC_VALUE`（評価額方式）：評価額（`PropertyTaxValuation`）× 所有持分 × 課税標準の特例率。課税団体ごとに合計し、合計に対して免税点を判定する。課税標準と税額は切り捨てる。
  - `LSPC_CALC_UNIT`（定額方式）：区分（`rateClassCode`）ごとの年額 × 台数 × 所有持分。免税点はない。
- **課税団体**（`LEASE_PCHG_JUR`）：市町村（`LSPC_JUR_MUNI`）、都道府県（`LSPC_JUR_PREF`、市町村の `GeoAssoc REGIONS` の親）、固定（`LSPC_JUR_FIXED`、国 `JPN` など）。
- **物件種別と税目**（`LeasePublicChargeAssetType`）：物件種別、またはいちばん近い親の種別に行があれば、その行の税目だけを対象にする（`applicable=N` は対象外）。どの種別にも行がなければ、`appliesToUnmapped=Y` の税目（償却資産税）を対象にする。
- **所有持分**：`LeaseAgreementAsset.ownershipShare`（空なら 1）。申告は持分で按分し、免税点も按分後の額で判定する。見積の見込み額は物件全体で計算する（借手が負担するのは公租公課相当額の全額のため）。

### 12.2 データモデル（`entitymodel_lease.xml`）

| エンティティ | 主キー | 内容 |
|--------------|--------|------|
| `LeasePublicChargeType` | publicChargeTypeId | 税目と計算規則（12.1） |
| `LeasePublicChargeAssetType` | fixedAssetTypeId + publicChargeTypeId | 物件種別ごとの税目。対象外の指定、課税標準の特例率 `taxableBaseRatio` |
| `LeasePublicChargeRate` | leasePublicChargeRateId | 税目 × 課税団体（× 区分）の税率 `taxRate`（評価額方式）または年額 `unitAmount`（定額方式）、標準行のフラグ、期間 |
| `LeaseAssetPublicCharge` | leaseAgreementId + assetSeqId + publicChargeTypeId | 定額方式の物件の区分（排気量・重量 等） |
| `LeasePublicChargeDecl` | leasePublicChargeDeclId | 申告：組織 × 税目 × 課税年度 × 課税団体で一意。`legacyDeclId`（移行元の L10 申告） |
| `LeasePublicChargeDeclAsset` | declId + fixedAssetId | 物件の明細：持分、物件全体の評価額 `fullAssessedValue`、持分後の評価額 `assessedValue`、特例率、課税標準の基礎額 `taxableValue`、区分・年額・税額 |
| `LeasePublicChargePayment` | declId | 納付と費用計上 |
| `LeaseAgreementAsset`（追加列） | — | `ownershipShare` |

L10 のテーブル名との対応：`LeasePropertyTaxRate` → `LeasePublicChargeRate`（`municipalityGeoId` → `jurisdictionGeoId`）、`LeasePropertyTaxDecl` → `LeasePublicChargeDecl`、`LeasePropertyTaxDeclAsset` → `LeasePublicChargeDeclAsset`、`LeasePropertyTaxPayment` → `LeasePublicChargePayment`。
旧エンティティは移行のためだけに定義を残している（業務ロジックでは使わない）。旧テーブルは AGENTS.md の規則に従って削除していない。全環境で移行が終わったら、定義とテーブルを削除できる。

### 12.3 seed

| 種類 | 値 |
|------|----|
| 税目 | `LSPC_DEPR_ASSET` 償却資産税（評価額方式・市町村・1/1・免税点 150 万円・1000 円／100 円切捨・1.4%・6 年・`LSCMP_PROP_TAX`、`appliesToUnmapped=Y`）、`LSPC_AUTO_TAX` 自動車税種別割（定額方式・都道府県・4/1・`LSCMP_AUTO_TAX`）、`LSPC_WEIGHT_TAX` 自動車重量税（定額方式・固定 `JPN`・年額換算・`LSCMP_WEIGHT_TAX`） |
| 物件種別 | `VEHICLE`：償却資産税は対象外、自動車税・重量税が対象。`VESSEL`（新設）：償却資産税、特例率 0.5（例示） |
| 税率・年額 | `LPTR_STANDARD` 1.4%、`LPTR_YOKOHAMA` 1.5%（ID は L10 と同じ）。自動車税 `PASS_1000`〜`PASS_3000`（25,000〜50,000 円）、重量税 `PASS_W1000`〜`PASS_W2500`（8,200〜20,500 円）。どれも例示 |
| 構成要素 | `LSCMP_AUTO_TAX`（自動車税相当額）、`LSCMP_WEIGHT_TAX`（自動車重量税相当額）を追加 |
| 状態 | `LEASE_PCHG_STATUS`：`LEASE_PCHG_GENERATED` → `LEASE_PCHG_PAID` |
| GL 科目タイプ | `LEASE_PCHG_EXPENSE`（601500 租税公課）／ `LEASE_PCHG_PAYABLE`（224600 未払租税公課）。科目番号は L10 と同じで、科目タイプを付け替えた。demo・テストデータの Company への割当も新しい科目タイプに替えた |
| `finance.properties` | `lease.propertytax.*` を削除（値は税目の行に移した） |

### 12.4 サービス

| サービス | 内容 |
|----------|------|
| `resolveLeasePublicChargeEstimate` | 旧 `resolveLeasePropertyTaxEstimate`。`publicChargeTypeId`（既定は償却資産税）、`quantity`、`rateClassCode`、`taxableBaseRatio` を追加した。最初の課税年度は、開始日より後の最初の賦課期日の年とする |
| `setLeaseAssetPublicChargeAttributes` | 新規。契約物件の所在市町村、耐用年数、所有持分（0 より大きく 1 以下）、税目ごとの区分を設定する |
| `generateLeasePublicChargeDeclarations` | 旧 `generateLeasePropertyTaxDeclarations`。税目を指定しなければ全税目を処理する。物件種別で対象の税目を決め、賦課期日より前に取得した物件だけを対象にする。所在や区分を解決できない物件は `skippedAssetCount` に数える |
| `recordLeasePublicChargePayment` | 旧 `recordLeasePropertyTaxPayment`。税目の GL 科目タイプで計上する |
| `migrateLeasePropertyTaxData` | 新規。L10 の行を償却資産税として新しいテーブルにコピーする。何度実行しても同じ結果になる（税率は ID、申告は `legacyDeclId` で重複を判定）。同じ組織・年度・団体の申告が既にあれば、コピーせず `declarationConflicts` に数える。旧テーブルの行は残す |

見積（`createLeaseQuote`）：税目に対応する構成要素で金額を指定しない場合、見込み額を自動で設定する。対象は、見積の物件のうちその税目がかかる物件で、特例率ごとにまとめて見込む。市町村・都道府県が課税団体の税目には、所在（`municipalityGeoId`）が必要。対象の物件がない場合はエラーにする（例：車両に固定資産税相当額）。

### 12.5 画面

- メニュー「公租公課」（`FindLeasePublicCharge`）。生成フォームに税目の選択（空なら全税目）を追加し、一覧に税目と課税団体の列を追加した。

### 12.6 テスト（`LeasePublicChargeTests`。旧 `LeasePropertyTaxTests` の 4 件を移し替え、5 件を追加）

| テスト | 内容 |
|--------|------|
| （L10 の 4 件） | 新しい名前のサービス・テーブル・状態で、同じ内容を検証する |
| `testCalculatorShareRatioAndUnit` | 持分・特例率の適用、免税点を課税標準で判定すること、取得年に課税しないこと、定額方式の年額 × 台数 × 持分と合計、不正な入力の拒否 |
| `testAssetTypeChargeResolution` | 一般の物件＝償却資産税、車両＝自動車税・重量税、船舶＝償却資産税（特例率 0.5）、子の種別は親の設定を引き継ぐ |
| `testVehicleEstimateAndDeclaration` | 車両に固定資産税相当額は付けられない。自動車税・重量税の見込み額（年額 / 12）。自動車税は都道府県に申告し、区分のない車両はスキップする。車両には償却資産税がかからない。納付の仕訳 |
| `testCoOwnedAssetShare` | 持分の不正な値の拒否。持分 1/2 で按分し、物件全体では免税点以上でも、持分後は免税点未満になる |
| `testMigrationFromLegacy` | 税率・申告・明細・納付の移行、移行した税率が使われること、2 回目の実行で何も増えないこと |

### 12.7 検証

| 検証項目 | 結果 |
|----------|------|
| XML の整形式 | 変更した 11 ファイルで OK |
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 7 テーブル、`LEASE_AGREEMENT_ASSET.OWNERSHIP_SHARE`、308 行を変更 |
| `gradlew "ofbiz --test component=finance"` | tests=143 / errors=0 / failures=0 |
| demo 投入 | BUILD SUCCESSFUL（224 行を変更） |
| 移行 | テスト内で `migrateLeasePropertyTaxData` を既定 DB に対して実行した（テスト用 delegator は既定 DB を共有するため、開発 DB の L10 の行も移行済み） |
| 画面 | 未確認（定義のみ） |

### 12.8 要確認・制約

- **税額は例示**：自動車税・重量税の年額、船舶の特例率 0.5 は動作確認用の例示。本番の値（登録時期・用途・環境性能による違い、重量税の車検期間ごとの納付と年額換算、船舶の特例の条件）は税理士の確認を得てから設定する。
- **重量税の納付時期**：実際には車検時に複数年分をまとめて納付する。今は年額換算で 4/1 の年次申告として扱う。車検ごとの納付は L19（自動車リース）で扱う。
- **共有物の納税**：共有物は共有者が連帯して納税義務を負う。今は貸手の持分だけを申告・費用計上している。全額申告と持分精算の扱いは L16（協調リース）で決める。
- **持分の置き場所**：物件の持分は `LeaseAgreementAsset.ownershipShare` に持たせた。L10a の `FinanceContractParty` は契約の持分なので別物。L16 で両者の関係（契約の持分から物件の持分を作る 等）を決める。
- **申告書の様式**：償却資産申告書・種類別明細書の様式出力は対象外（L10 と同じ）。

---

## 13. STEP L11 — メンテナンスリース

保守料（サービス部分）を L9 の構成要素 `LSCMP_MAINTENANCE` として扱い、リース料部分と分けて保守料収益に計上する。
あわせて、物件の保守計画、保守予定（core `FixedAssetMaint` と作業管理のタスク）、保守の実績と保守業者の費用（保守原価）を管理する。

実装：`LeaseComponentSettings`（構成要素の種類ごとの収益科目と区分の強制）、`LeaseMaintenanceServices`（計画・予定・実績・集計）。
`LeaseAccountingServices` の請求と `LeaseQuoteServices.groovy` の見積を変更した。

### 13.1 決定事項（2026-10-15）

| 項目 | 決定 |
|------|------|
| 保守料の収益計上 | **専用科目（403900 保守料収益）に請求時に計上**する。毎月同額の保守料は、役務を毎月同じだけ提供したものとみなす。保守料は必ず「区分」とし、「含める」（第1法の売上高に含める）は見積で拒否する |
| 保守原価 | **保守業者の仕入請求書**（core `PURCHASE_INVOICE`）で計上する：借 保守原価（503900）／ 貸 買掛金。支払・消込は core の機能で行う（L3 の物件購入と同じ） |
| 保守予定の作成 | **バッチで先に作る**（`runLeaseMaintScheduleBatch`、既定は 2 か月先まで、`lease.maint.schedule.months`）。同じ計画・同じ予定日の予定は 1 件だけ（冪等）。L7 の満了バッチと同じ方式 |

### 13.2 データモデル（`entitymodel_lease.xml` に追加）

| エンティティ | 主キー | 内容 |
|--------------|--------|------|
| `LeaseComponentRevenueAccount` | componentTypeEnumId | 構成要素の種類ごとの収益科目タイプ（区分のとき）と、区分の強制 `separateRequired`（seed）。行がない種類は従来どおり 403800 |
| `LeaseMaintPlan` | leaseAgreementId + maintPlanSeqId | 保守計画：物件、保守種別（core `ProductMaintType`）、間隔（月）、初回予定日、保守業者、見込み費用、終了日 |
| `LeaseMaintRecord` | leaseMaintRecordId | 1 回の保守（計画による予定、または臨時の修理）：予定日、状態（`LEASE_MAINT_STATUS`）、core `FixedAssetMaint`・タスク（`WorkEffort`）への参照、完了日、保守業者、費用（税抜）、仕入請求書 |

core の `FixedAssetMaint` は物件の保守履歴として作り（`scheduleWorkEffortId` にタスク、間隔は `TF_mon`）、状態を `FAM_SCHEDULED` → `FAM_COMPLETED` / `FAM_CANCELLED` に合わせる。
業務の状態の正は `LeaseMaintRecord.statusId` で、タスク（`CAL_*`）は作業が済んだかだけを表す（要件 3.5 の原則）。core は変更していない。

### 13.3 seed

| 種類 | 値 |
|------|----|
| GL 科目タイプ | `LEASE_MAINT_REVENUE`（403900 保守料収益、`SALES_ACCOUNT` 配下）、`LEASE_MAINT_COST`（503900 保守原価、`COGS_ACCOUNT` 配下）。Company への割当は demo・テストデータに追加 |
| 構成要素の設定 | `LeaseComponentRevenueAccount`：`LSCMP_MAINTENANCE` → `LEASE_MAINT_REVENUE`、`separateRequired=Y` |
| 請求明細種別 | `LEASE_MAINT_COST`（`PURCHASE_INVOICE` への `InvoiceItemTypeMap`） |
| 状態 | `LEASE_MAINT_STATUS`：`LSMNT_SCHEDULED` → `LSMNT_COMPLETED` / `LSMNT_CANCELLED` |
| 作業種別 | `FINWE_MAINTENANCE`（保守作業、共通層 `FinanceCommonTypeData.xml`） |
| 保守種別 | core `ProductMaintType` に `LEASE_INSPECTION`（点検）、`LEASE_REPAIR`（修理）を追加 |
| `finance.properties` | `lease.maint.schedule.months=2` |

### 13.4 請求（L9 の変更）

- 区分の構成要素は、種類ごとの収益科目（`LeaseComponentSettings.revenueAccountType`）で請求明細を分ける。保守料は 403900、それ以外（固定資産税相当額 等）は従来どおり 403800。
- 消費税・回収状況・延滞の扱いは L9 と同じ（保守料は構成要素として充当・延滞の対象。遅延損害金の基礎には含めない）。

| 借方 | 貸方 | 金額 |
|------|------|------|
| 売掛金（core） | リース投資資産／リース債権 | リース料 |
| 売掛金（core） | 保守料収益（403900） | 保守料 |
| 売掛金（core） | 仮受消費税（224500）／ core 税当局科目 | リース料・保守料の消費税 |

### 13.5 サービス

| サービス | 内容 |
|----------|------|
| `createLeaseMaintPlan` | 契約済・有効な契約の物件に保守計画を登録する。間隔は 1 か月以上、保守種別は core に存在するもの。初回予定日を省略すると、リース開始日＋間隔（有効な契約では今日以降の最初の間隔日） |
| `endLeaseMaintPlan` | 計画を終了する。終了日以降の予定は取り消す（記録・`FixedAssetMaint`・タスク） |
| `runLeaseMaintScheduleBatch` | 有効な契約の計画から、処理日＋N か月までの予定を作る。リース終了日・計画の終了日を超えない。貸し出し中でない物件の計画は `skippedPlanCount`。計画×予定日で冪等。タスクは契約のロックの下で作る |
| `createLeaseMaintenance` | 臨時の保守（修理。既定 `LEASE_REPAIR`）を登録する |
| `completeLeaseMaintenance` | 実績を記録する。費用が正なら保守業者の仕入請求書を作って READY にし、仕訳を転記する（借 503900 ／ 貸 買掛金）。費用 0（保証修理 等）は仕訳なし。費用があるのに業者がなければエラー。記録・`FixedAssetMaint`・タスクを完了にする |
| `cancelLeaseMaintenance` | 予定を取り消す（記録・`FixedAssetMaint`・タスク） |
| `getLeaseMaintenanceSummary` | 契約上の保守料総額、請求済み保守料（403900 の請求明細）、保守原価（完了分）、差額、件数 |

共通層の `FinanceWorkEffortHelper` に `cancelTask` と作業種別 `TYPE_MAINTENANCE` を追加した（ローン層・リース層のクラスは参照しない）。

### 13.6 画面

- `ManageLeaseAgreement` に「保守」のスクリーンレットを追加した：保守料収益と保守原価の集計、保守計画の登録・一覧（終了リンク）、保守予定の作成（バッチ）、臨時保守の登録、保守予定・実績の一覧（取消リンク）、実績の記録フォーム（費用・業者・請求書番号）。
- タスクは既存の「契約のタスク」一覧と workeffort アプリに表示される。

### 13.7 テスト（`LeaseMaintenanceTests`。USD 商品 `TEST_LEASEPROD_USD`、60 か月、テストごとに新しい借手）

| テスト | 内容 |
|--------|------|
| `testMaintenanceFeeBilledAsSeparateRevenue` | 保守料を「含める」とした見積の拒否。請求で保守料が 403900 に計上され、403800・売上（403500）には計上されない。貸借一致。集計の契約上の保守料総額・請求済み保守料 |
| `testMaintenancePlanSchedule` | 不正な計画の拒否。バッチで予定日ごとに記録・`FixedAssetMaint`・タスク（物件の割当つき）ができる。冪等。期間を延ばすと新しい予定日だけ増える。計画の終了で以降の予定を取消し（`FAM_CANCELLED`・`CAL_CANCELLED`）、その後のバッチで増えない |
| `testMaintenanceCompletionBooksCost` | 臨時修理の登録。負の費用の拒否。実績で仕入請求書（READY、転記済み、借 503900 ／ 貸 買掛金）。記録・`FixedAssetMaint`・タスクが完了。完了後の再完了・取消の拒否。業者のない有償実績の拒否、無償実績は仕訳なし。集計の差額 |

L9 の `testBillingSeparateAndIncludedComponents` は「含める」の例を保守料から保険料（`LSCMP_INSURANCE`）に変えた（保守料は区分のみになったため）。

### 13.8 検証

| 検証項目 | 結果 |
|----------|------|
| XML の整形式 | 変更した 11 ファイルで OK |
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 3 テーブル（`LEASE_COMPONENT_REVENUE_ACCOUNT` / `LEASE_MAINT_PLAN` / `LEASE_MAINT_RECORD`）、324 行を変更 |
| `gradlew "ofbiz --test component=finance"` | tests=150 / errors=0 / failures=0（L11 の 3 件を含む） |
| demo 投入 | BUILD SUCCESSFUL（228 行を変更） |
| 画面 | 未確認（定義のみ） |

### 13.9 要確認・制約

- **保守料の収益認識**：請求時に計上する（毎月同額＝役務の均等提供とみなす）。保守の実施時期に偏りがある契約（年 1 回の大規模点検 等）で、実施に合わせて収益を認識する必要があるかは会計方針として確認する。
- **保守原価の対応**：保守原価は実績の都度計上し、保守料収益との期間対応（引当・前払）はしない。集計の差額は契約全体の目安。
- **保守料の消費税**：見積で指定した区分（通常は課税）に従う。保守業者からの仕入の消費税（仮払消費税）は仕入請求書に含めていない（税抜の費用だけを計上）。仕入税額控除を扱う場合は請求書に税明細を追加する（要確認）。
- **中途解約・満了**：解約（L14）や満了で計画を自動終了しない。満了時は `endLeaseMaintPlan` を使う。L14 で連動させる。
- **メーター（走行距離・稼働時間）による保守**：月の間隔だけ。core の `FixedAssetMeter` による保守間隔は後続（L19 の車検・点検で検討）。
- **保守業者への支払**：仕入請求書の支払・消込は core の画面・サービスで行う。finance 側に支払画面は作っていない。

---

## 14. STEP L12 — オペレーティングリース

物件を貸手の固定資産（賃貸資産）として計上し、リース期間定額法で減価償却する。受取リース料は請求時に収益に計上する。
ファイナンスリースの売上高・売上原価・繰延リース利益は使わない。申込・審査・見積・契約・検収・請求・入金・延滞・満了の流れは
L1〜L11 と同じで、契約の収益計上方法（`LeaseRevenueStrategy`）で会計だけを切り替える。

実装：`LeaseRevenueStrategy.Operating`（開始・請求の仕訳）、`LeaseDepreciationServices`（減価償却）。
`LeaseProductServices.groovy`・`LeaseTaxHelper`・`LeaseAcquisitionServices`・`LeaseAccountingServices`・`LeaseContractAdapter`・
`LeaseExpiryServices`・`LeasePublicChargeServices` に分岐を追加した。

### 14.1 決定事項（2026-10-15）

| 項目 | 決定 |
|------|------|
| 減価償却の方法 | **リース期間定額法**：償却期間＝リース期間、残存価額＝契約の見積残価（取得価額で物件に按分）。月ごとに償却し、最終月で端数を調整して帳簿価額を残価に一致させる |
| 受取リース料の収益計上 | **請求時に計上**（借 売掛金 ／ 貸 受取リース料 404000）。毎月の請求が 1 か月分の賃貸に対応する前提 |
| core の償却サービス | 使わない。`calculateFixedAssetDepreciation` は現在日からの年単位の計算で仕訳を作らないため。core の `FixedAsset` は償却の結果に合わせて更新する（`salvageValue`・`expectedEndOfLife`・`depreciation`） |
| 消費税 | 資産の貸付けとして**各回のみ**（開始時一括は不可。商品の設定で拒否し、検収時も各回に固定） |

### 14.2 商品・契約

- 商品種別 `LEASE_OPERATING` → リース区分 `LEASE_CLS_OPERATING`（L1 で「未対応」として拒否していたのを解除）。
- 収益計上方法 `LEASE_REV_OPERATING` を追加。オペレーティングの商品は既定でこの方法になり、他の方法は指定できない。ファイナンスリースの商品にこの方法は指定できない（`FinanceLeaseRevenueMethodClassMismatch`）。
- 見積は L2 と同じ計算（月額＝取得価額 × 料率、計算利子率で残価を回収）。リース料表の元本・利息相当額の内訳は保持するが、オペレーティングの会計では使わない（下記）。

### 14.3 会計

| 時点 | 借方 | 貸方 | 金額 |
|------|------|------|------|
| 検収（開始） | 賃貸資産（165500） | リース物件仮勘定（147500） | 取得価額 |
| 請求 | 売掛金（core） | 受取リース料（404000） | リース料 |
| 請求 | 売掛金（core） | 仮受消費税（224500）／ core 税当局科目 | 消費税 |
| 減価償却（月次） | 賃貸資産減価償却費（503800） | 賃貸資産減価償却累計額（165900） | 月の償却額 |
| 処分（売却・廃棄） | 物件売却未収金（128900）・減価償却累計額（165900） | 賃貸資産（165500）・処分益（403700）／処分損（503700） | 売却額・累計額・取得価額・差額 |

- 売上高（403500）・売上原価（503500）・繰延リース利益（128700）は計上しない。繰延リース利益の戻入（`recognizeLeaseProfit`）は何もしない（0 件）。
- 構成要素（L9）・保守料（L11）の請求はファイナンスリースと同じ。

### 14.4 減価償却（`LeaseDepreciationServices`）

- 償却額：物件ごとに 償却対象額＝取得価額 − 按分した残価。k か月目までの累計＝償却対象額 × k / 期間（通貨別端数処理）、月の償却額＝累計の差。最終月は残りの全額。
- `depreciateLeaseAssets`：有効なオペレーティングリースについて、指定日（既定は今日）までに終わったリース月のうち未償却の月を償却する。物件×月で 1 件の `LeaseAssetDepreciation`、月ごとに 1 件の仕訳。物件×月で冪等。core の `FixedAsset.depreciation` に加算する。
- 月次バッチ（`runLeaseAgreementMonthlyAccounting`）が請求・戻入の後に償却を行う（`createdDepreciationCount`）。
- 検収時に core `FixedAsset` の `salvageValue`（按分した残価）・`expectedEndOfLife`（リース終了日）・`depreciation`（0）を設定する。

### 14.5 回収状況・延滞

- 回収状況（`computeBalance`）：リース料はすべて受取リース料として扱い、元本回収額は 0、未回収元本・繰延リース利益残高は 0。入金の充当は「消費税 → 構成要素 → リース料」。オペレーティングでは `leasedAssetCost`・`accumulatedDepreciation`・`leasedAssetBookValue` を返す。
- 延滞（`LeaseContractAdapter`）：未払のリース料は利息相当額のバケット（＝受取リース料）に入れ、元本は 0。遅延損害金の計算基礎はファイナンスリースと同じ（リース料、`lease.latefee.base` で消費税の算入を切替）。

### 14.6 満了

- 所有権移転はできない（`transferLeaseOwnership` は L7 のとおり移転リース以外を拒否）。
- 返還（`returnLeaseAsset`）：リース終了日まで償却を済ませてから、精算記録に取得価額・減価償却累計額を記録する。`residualValue` は帳簿価額（＝残価）。
- 処分（`disposeLeaseAsset`）：帳簿価額で処分する（14.3 の仕訳）。売却額 − 帳簿価額が処分損益。
- 契約の完了（`tryComplete`）でもリース終了日までの償却を済ませる。
- `LeaseAssetSettlement` に `acquisitionCost`・`accumulatedDepreciation` の列を追加した（オペレーティングのみ使用）。

### 14.7 公租公課（L10b）

- 申告の対象にオペレーティングリースの物件（貸手所有）を加えた（所有権移転外ファイナンスリースと同じ扱い）。
- 会計上の減価償却（本 STEP）と償却資産税の評価額（L10）は独立して計算する。

### 14.8 データモデル・seed

| 種類 | 値 |
|------|----|
| エンティティ | `LeaseAssetDepreciation`（leaseAgreementId + assetSeqId + periodNumber：期間、償却額、累計、帳簿価額、仕訳）。`LeaseAssetSettlement` に 2 列追加 |
| 収益計上方法 | `LEASE_REV_OPERATING` |
| GL 科目タイプ | `LEASE_OPER_ASSET`（165500 賃貸資産、`FIXED_ASSET` 配下）、`LEASE_OPER_ACCUM_DEP`（165900 減価償却累計額）、`LEASE_OPER_DEPR`（503800 減価償却費、`COGS_ACCOUNT` 配下）、`LEASE_OPER_REVENUE`（404000 受取リース料、`SALES_ACCOUNT` 配下）。Company への割当は demo・テストデータに追加 |
| テストデータ | USD のオペレーティングリース商品 `TEST_LEASEPROD_OPER`（料率 0.016、残価率 20%） |

### 14.9 画面

- `ManageLeaseAgreement`：回収状況にオペレーティングのときだけ減価償却累計額・帳簿価額を表示。「賃貸資産の減価償却」のスクリーンレット（実行フォーム・償却の一覧）を追加した。

### 14.10 テスト（`LeaseOperatingTests`）

| テスト | 内容 |
|--------|------|
| `testOperatingProductRules` | オペレーティングの商品は既定で `LEASE_REV_OPERATING`、第1法・開始時一括の消費税は拒否。ファイナンスリースに `LEASE_REV_OPERATING` は拒否 |
| `testOperatingStartAndBilling` | 開始仕訳（借 165500 ／ 貸 147500、売上・原価・繰延利益なし、一括消費税なし）、`FixedAsset` の設定、請求で 404000 と 224500、戻入 0 件、入金後の元本回収 0・未回収元本 0 |
| `testDepreciation` | 定額の月額と最終月の調整、月次バッチで 3 か月分、期間・累計・帳簿価額、仕訳（借 503800 ／ 貸 165900）、`FixedAsset.depreciation`、冪等、ファイナンスリースでは拒否 |
| `testExpiryReturnAndDisposal` | 12 か月で残価まで償却、所有権移転の拒否、返還時の精算記録（帳簿価額・取得価額・累計額）、売却で 128900・165900・165500・403700、貸借一致 |
| `testOperatingAssetDeclared` | オペレーティングの物件が償却資産税の申告に含まれる |

### 14.11 検証

| 検証項目 | 結果 |
|----------|------|
| XML の整形式 | 変更した 10 ファイルで OK |
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 1 テーブル（`LEASE_ASSET_DEPRECIATION`）、`LEASE_ASSET_SETTLEMENT` に 2 列、333 行を変更 |
| `gradlew "ofbiz --test component=finance"` | tests=166 / errors=0 / failures=0（L12 の 5 件を含む、18.7 分。開発 DB を `cleanAll loadAll` で作り直した後） |
| demo 投入 | BUILD SUCCESSFUL |
| 画面 | 未確認（定義のみ） |

### 14.12 要確認・制約

- **会計基準**：新リース会計基準（2027 年 4 月以降開始の事業年度から適用）は借手側の改正が中心で、貸手のオペレーティングリースの会計（賃貸資産の計上・減価償却・受取リース料）は従来と同じと判断した。適用時に貸手側の開示・判定基準を確認する（要確認）。
- **リース区分の判定**：ファイナンス／オペレーティングの判定（現在価値基準・経済的耐用年数基準）はしていない。商品種別で区分を決める（L1 と同じ）。
- **税務上の償却**：会計上はリース期間定額法。税務上の減価償却（法定耐用年数）との差異（申告調整）は扱わない。
- **受取リース料の期間対応**：請求時に計上するため、前払・後払で請求の期間と会計期間がずれる場合の前受・未収は計上しない。期末に期間対応が必要なら按分方式を追加する。
- **減損・中途解約**：減損や中途解約（L14）による償却の打切り・残存簿価の処理は扱わない。
- **再リース**：満了後の再リース（L13）では、残価（帳簿価額）を再リース期間で償却するかを L13 で決める。→ L13 で「再リース期間で 0 まで償却する」と決定（15 章）。

---

## 15. STEP L13 — 再リース

満了した所有権移転外のリース（ファイナンスリース・オペレーティングリース）を、同じ契約のまま再リース期間（既定 12 か月）で更新する。
再リース料は期間の初めに年額を前払いで請求し、前受再リース料から毎月按分して収益に計上する。再リース開始時に残っている物件の簿価は、
再リース期間で 0 まで償却する。

実装：`LeaseReLeaseServices`。`LeaseAccountingServices`（月次バッチ・回収状況・入金）、`LeaseContractAdapter`（延滞・未決済請求書）、
`LeaseExpiryServices`（返還・完了・満了バッチ）、`LeaseMaintenanceServices`（保守予定の期限）に連動を追加した。

### 15.1 決定事項（2026-10-15）

| 項目 | 決定 |
|------|------|
| 再リースの持ち方 | **元の契約に再リース期間（`LeaseReLease`）を追加する**。契約は再リース中も `LEASEAGR_ACTIVE`。返還・完了は最後の再リース期間の終了後（`effectiveEndDate`）。物件・保守計画・公租公課・延滞の仕組みはそのまま使う |
| 再リース料の収益計上 | **前受→月次で按分**：請求時は前受再リース料（224700）に計上し、経過した月の分を受取再リース料（404100）へ振り替える（毎月同額、最終月で端数調整） |
| 残存簿価 | **再リース期間で償却**：ファイナンスリースは最初の再リース開始時に見積残価をリース投資資産（128500）から賃貸資産（165500）へ振り替える。オペレーティングリースは賃貸資産の帳簿価額（＝残価）のまま。どちらも再リース期間で 0 まで定額償却する（L12 の科目・`LeaseAssetDepreciation` を使う）。残価が 0 なら仕訳なし |
| 再リース料の既定値 | **元の月額リース料 × 年額係数 × 月数 / 12**。係数は商品の `FinancialProductLease.reLeaseAnnualFactor`、なければ `lease.release.annual.factor`（既定 1.0＝年額が月額 1 か月分）。登録時に金額を指定すればそれを使う |

### 15.2 データモデル

| エンティティ | 主キー | 内容 |
|--------------|--------|------|
| `LeaseReLease` | leaseAgreementId + reLeaseSeqId | 再リース期間：開始日（前の終了日の翌日）・終了日・月数・再リース料（税抜）・消費税（率・額・税当局）・状態（`LEASE_RELEASE_STATUS`）・請求書・残価振替の仕訳・収益計上済額 |
| `LeaseReLeaseRecognition` | leaseAgreementId + reLeaseSeqId + monthNumber | 月ごとの収益計上（金額・累計・仕訳）。冪等 |
| `FinancialProductLease`（追加列） | — | `reLeaseAnnualFactor` |
| `LeaseAgreementAsset`（追加列） | — | `reclassifiedResidual`（ファイナンスリース：賃貸資産へ振り替えた残価） |
| `LeaseAssetDepreciation`（追加列） | — | `reLeaseSeqId`。再リースの月は `periodNumber`＝リース期間＋それまでの再リースの月数＋月 |

状態：`LSREL_CONTRACTED`（登録）→ `LSREL_ACTIVE`（請求・開始）→ `LSREL_COMPLETED`（全月計上）、`LSREL_CONTRACTED` → `LSREL_CANCELLED`。状態は再リースのサービスだけが変える。

### 15.3 会計

| 時点 | 借方 | 貸方 | 金額 |
|------|------|------|------|
| 開始（請求） | 売掛金（core） | 前受再リース料（224700） | 再リース料 |
| 開始（請求） | 売掛金（core） | 仮受消費税（224500）／ core 税当局科目 | 消費税（開始日の税率） |
| 開始（ファイナンス・最初の再リース） | 賃貸資産（165500） | リース投資資産（128500） | 見積残価 |
| 毎月 | 前受再リース料（224700） | 受取再リース料（404100） | 再リース料 × 1 / 月数 |
| 毎月 | 賃貸資産減価償却費（503800） | 減価償却累計額（165900） | 開始時の簿価 × 1 / 月数 |
| 返還後の処分 | 物件売却未収金（128900）・減価償却累計額（165900） | 賃貸資産（165500）・処分益（403700）／処分損（503700） | L12 と同じ（帳簿価額で処分） |

- 開始の前に、元のリースの繰延リース利益の戻入（ファイナンス）・減価償却（オペレーティング）をリース終了日まで済ませる。
- 再リース料の消費税は、再リースを新たな貸付けとみなし、開始日に有効な税率で各回（年 1 回）計上する。

### 15.4 サービス

| サービス | 内容 |
|----------|------|
| `createLeaseReLease` | 有効な所有権移転外の契約（物件がすべて貸出中）に次の再リース期間を登録する。開始日は前の終了日（リース終了日または前の再リース）の翌日。月数（既定 `lease.release.months`=12）・再リース料（既定は 15.1）を指定できる。所有権移転リースは拒否 |
| `cancelLeaseReLease` | 未請求（`LSREL_CONTRACTED`）の最後の期間だけ取り消せる |
| `billLeaseReLease` | 再リースを開始する：請求（期日＝開始日）、ファイナンスの残価振替、`LSREL_ACTIVE`。冪等。期間前でも請求できる |
| `processLeaseReLeases` | 処理日までに始まった期間を開始し、終わった月の収益計上と償却を行う。全月を計上した期間は `LSREL_COMPLETED`。冪等。月次バッチ（`runLeaseAgreementMonthlyAccounting`）が請求・戻入・償却の後に呼ぶ（開始した件数は `createdInvoiceCount`、償却した月数は `createdDepreciationCount` に含める） |

### 15.5 既存処理との連動

- **回収状況**（`computeBalance`）：再リースの請求額・消込額を `billedAmount`・`appliedAmount`（＝未払額）に含め、`billedReLeaseAmount`・`collectedReLeaseAmount`（消費税を先に充当）・`recognizedReLeaseAmount`・`unearnedReLeaseAmount` を返す。
- **入金**（`receiveLeasePayment`）：再リースの請求書も消込の対象（期日の古い順）。
- **延滞**（`LeaseContractAdapter`）：未払の再リース請求書を延滞額に含める（再リース料は利息相当額のバケット、元本 0）。未決済の請求書（完了の条件）にも含める。
- **返還・完了**：返還日・完了日は最後の再リース終了日以降。返還時に再リースの計上・償却を終了日まで済ませ、ファイナンスの振替済み残価は帳簿価額で精算記録に残す（`acquisitionCost`＝振替額、`accumulatedDepreciation`）。処分は L12 と同じく帳簿価額で行う。
- **満了バッチ**：返還タスクは最後の再リース終了日以降に作る（満了通知は元のリース終了日のまま）。
- **保守計画**（L11）：予定日は最後の再リース終了日まで作る。
- **公租公課**（L10b）：契約が有効なので、再リース中の物件も申告の対象（変更なし）。

### 15.6 画面

- `ManageLeaseAgreement` に「再リース」のスクリーンレット（登録フォーム、期間の一覧と請求・取消のリンク）を追加。商品条件の画面に年額係数を追加。

### 15.7 テスト（`LeaseReLeaseTests`。USD の `TEST_LEASEPROD_USD`（残価 120,000、料率 0.08）と `TEST_LEASEPROD_OPER`（残価 850,000、料率 0.016）、12 か月）

| テスト | 内容 |
|--------|------|
| `testReLeaseRegistration` | 不正な月数・金額の拒否、開始日・終了日、既定の再リース料＝月額、2 期目の連続、最後でない期間の取消の拒否と最後の期間の取消、再リース中の返還の拒否 |
| `testReLeaseBillingAndRecognition` | 月次バッチで開始：請求（224700・224500、期日＝開始日、税率 10%）、残価振替（借 165500 ／ 貸 128500）、1 か月分の収益計上（224700 → 404100）と償却（10,000、`periodNumber`=13）、冪等、満了バッチで返還タスクを作らない、延滞に再リース請求書（元本 0）、回収状況と全額入金 |
| `testOperatingReLeaseReturnAndDisposal` | 再リース満了：12 か月の計上・償却（償却 24 件）、帳簿価額 0、前受 0、返還で契約完了、売却（165900・165500・403700） |
| `testFinanceReLeaseReturn` | ファイナンスの再リース満了後の返還：精算記録（振替額 120,000・累計 120,000・簿価 0）、契約完了、廃棄（借 165900 ／ 貸 165500、損益なし） |

### 15.8 検証

| 検証項目 | 結果 |
|----------|------|
| XML の整形式 | 変更した 10 ファイルで OK |
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 2 テーブル（`LEASE_RE_LEASE` / `LEASE_RE_LEASE_RECOGNITION`）、3 列追加、347 行を変更 |
| `gradlew "ofbiz --test component=finance"` | tests=174 / errors=0 / failures=0（L13 の 4 件を含む）。所要 45 分（L12 時点の DB 再構築から 2 回の全体テスト＋単体実行で DB が増えたため） |
| demo 投入 | BUILD SUCCESSFUL |
| 画面 | 未確認（定義のみ） |

`--test case=lease-release-tests` の単体実行は、テストデータ（`FinanceTestData.xml`）を投入する最初のテストケースが走らないため、DB 再構築直後は商品がなく失敗する（L12 では直前の全体テストのデータが残っていたため単体で通った）。単体実行は全体テストを一度流した後に行う。

### 15.9 要確認・制約

- **会計処理の根拠**：再リース料を期間按分で収益計上し、ファイナンスリースの残価を賃貸資産に振り替えて再リース期間で償却する処理は、リース会計の実務（再リース期間を解約不能期間に含めない場合）に沿ったものとして選んだ。採用する会計方針（振替の要否、備忘価額の残し方）は確認する（要確認）。
- **与信額**：再リース中も `FinanceContract` は有効のままで、与信の合算（`sumExposurePrincipal`）には元の取得価額が含まれ続ける。再リースの与信の扱いは要確認。
- **満了通知**：再リース期間の終了に対する満了通知（`LeaseExpiryNotice` は契約に 1 件）は作らない。再リースの更新案内は運用で行う。
- **期間途中の解約**：再リース期間中の解約（前受の返金・未償却簿価の処理）は L14（中途解約）で扱う。
- **月数**：再リース期間は月単位（既定 12 か月）。日割りはしない。

---

## 16. STEP L14 — 中途解約

有効なリースを期間の途中で解約する。規定損害金を算定して請求し、物件を引き揚げて処分する。
解約後は請求を止め、損害金と請求済みの未払リース料の回収、物件の返還（引揚）が済んだら契約を完了にする。

実装：`LeaseTerminationServices`。`LeaseReLeaseServices.terminateReLeases`、`LeaseAccountingServices`（入金・回収状況）、
`LeaseContractAdapter`（延滞・未決済請求書）、`LeaseExpiryServices`（返還・完了）、`LeasePublicChargeServices`、`LeaseInquiryHelper`、
`setLeaseAgreementStatus` に連動を追加した。

### 16.1 決定事項（2026-10-15）

| 項目 | 決定 |
|------|------|
| 規定損害金 | **未経過リース料の現在価値**：解約日より後が期日の未請求リース料を、契約の計算利子率 / 12（月利）で、解約日から各期日までの月数（端数は日割り）で割り引いた合計。構成要素・消費税は含めない。請求済みで未払いのリース料は損害金とは別に請求が残る |
| 見積残価 | **含めない（既定）**。物件は貸手に戻り、残価は物件の価値として貸手の帳簿に残るため。残価保証がある契約は `lease.termination.include.residual=true` で残価の現在価値を損害金に含める（その場合、引き揚げた物件は帳簿価額 0） |
| 消費税 | **不課税**（損害賠償金）。損害金の請求書に消費税を付けない |
| 物件の売却代金 | **損害金から控除しない**。損害金は解約時に確定し、売却代金は貸手の処分損益（L7 / L12 と同じ処分） |
| 再リース中の解約 | **返金しない**。前受再リース料の残りは解約日に受取再リース料へ一括で振り替える。損害金はない。未開始の再リース期間は取り消す |

> 選択肢の説明では「＋見積残価の現在価値」と書いたが、物件を引き揚げる（売却代金を控除しない）前提では残価を損害金に含めると二重回収になるため、既定は含めない形で実装し、設定で切り替えられるようにした。

### 16.2 状態

- `LEASEAGR_TERMINATED`（解約・引揚中）を追加：`LEASEAGR_ACTIVE` → `LEASEAGR_TERMINATED`（`terminateLeaseAgreement` のみ）→ `LEASEAGR_COMPLETED`（物件の返還とすべての請求書の決済後、満了サービス）。汎用の `setLeaseAgreementStatus` では解約できない。
- `FinanceContract` は回収中のため `FINCTR_ACTIVE` のまま（完了時に `FINCTR_COMPLETED`）。共通層の延滞処理（`LeaseContractAdapter.isActive`）・入金は解約後も対象。月次バッチ（請求・戻入・償却）は対象外。

### 16.3 解約の処理（`terminateLeaseAgreement`）

1. 解約日までに期日が来た回を請求する（通常の請求）。解約日まで繰延リース利益を戻し入れ（ファイナンス）、償却する（オペレーティング）。再リースは解約日までの計上を済ませる。
2. 損害金を算定する（16.1）。
3. ファイナンスリース（再リース前）：リース投資資産を精算する。

| 借方 | 貸方 | 金額 |
|------|------|------|
| 繰延リース利益（128700） | リース投資資産（128500）／リース債権（128600） | 未戻入の繰延リース利益 |
| 売掛金（core、損害金の請求書） | リース投資資産／リース債権 | 規定損害金（損害金がリース料債権に代わる） |
| リース投資資産／リース債権 | 解約益（404200） | 差額が正のとき |
| 解約損失（504200） | リース投資資産／リース債権 | 差額が負のとき |

   差額＝損害金＋引揚物件の帳簿価額（残価、残価を含める設定では 0）− 正味リース投資額（未請求リース料＋残価 − 未戻入の繰延リース利益）。精算後のリース投資資産の残高は残価（物件の帳簿価額）になり、返還・処分は L7 と同じ。

4. オペレーティングリース：損害金の請求書は解約益（404200）に計上する（損害金収入）。物件は帳簿価額（取得価額 − 減価償却累計額）のまま。
5. 再リース中：損害金なし。前受再リース料の残りを一括で受取再リース料へ（`LeaseReLeaseRecognition` に最後の行）、期間は `LSREL_COMPLETED`。再リースの償却は止まり、物件はその時点の帳簿価額で処分する。未開始の再リース期間は `LSREL_CANCELLED`。
6. 損害金の請求書（`LEASE_TERM_DAMAGES`、不課税、期日＝解約日＋`lease.termination.due.days`）を READY にして転記する。
7. 状態を `LEASEAGR_TERMINATED` にし、保守計画（L11）を解約日の翌日で終了（以降の予定は取消）、物件ごとに引揚タスク（`FINWE_ASSET_RETURN`）を作る。契約ごとに 1 回（冪等）。

`calculateLeaseTerminationDamages` は同じ算定を変更なしで返す（試算）。

### 16.4 解約後

- **入金**：`receiveLeasePayment` は解約後も使える。損害金の請求書も消込の対象（期日の古い順）。
- **回収状況**：損害金を請求額・消込額に含め、`terminationDamagesAmount`・`collectedTerminationAmount` を返す。解約後の未回収元本＝請求済み元本の未回収分＋損害金の未回収分（ファイナンス）。未請求リース料・繰延リース利益残高は 0。
- **延滞**：未払の損害金を延滞に含める（ファイナンス＝元本、オペレーティング＝リース料収益のバケット）。解約後の未請求の回は延滞に含めない。
- **引揚（返還）**：`returnLeaseAsset` は解約日以降に使える（所有権移転リースも可）。精算記録の帳簿価額は、ファイナンス＝精算後の残価（`residualCarried` を物件に按分）、オペレーティング・再リース後＝取得価額 − 減価償却累計額。処分（`disposeLeaseAsset`）は L7 / L12 と同じ。
- **完了**：物件がすべて返還され、すべての請求書（損害金を含む）が決済されたら `LEASEAGR_COMPLETED`。
- **公租公課**：解約・引揚中の物件も貸手所有のため申告の対象。
- **照会**：既定の表示対象に `LEASEAGR_TERMINATED` を含め、延滞日数も算出する。

### 16.5 データモデル・seed

| 種類 | 値 |
|------|----|
| エンティティ | `LeaseTermination`（leaseAgreementId：解約日・理由・未請求の回数と額・月利・中間利息控除額・残価を含めたか・損害金・未戻入の繰延リース利益・正味リース投資額・引揚物件の帳簿価額・解約損益・再リース料の一括計上額・請求書・仕訳） |
| 状態 | `LEASEAGR_TERMINATED` と遷移 2 本 |
| 請求明細種別 | `LEASE_TERM_DAMAGES`（`SALES_INVOICE`） |
| GL 科目タイプ | `LEASE_TERM_INCOME`（404200 解約益・解約損害金収入）、`LEASE_TERM_LOSS`（504200 解約損失）。Company への割当は demo・テストデータに追加 |
| `finance.properties` | `lease.termination.include.residual=false`、`lease.termination.due.days=0` |

### 16.6 画面

- `ManageLeaseAgreement` に「中途解約」のスクリーンレットを追加：有効な契約では解約日を指定した損害金の試算と解約フォーム、解約後は解約記録（損害金・中間利息控除額・解約損益・請求書）。解約後は物件の返還フォームが使える。

### 16.7 テスト（`LeaseTerminationTests`）

| テスト | 内容 |
|--------|------|
| `testDamagesCalculation` | 月数・現在価値の計算、試算（未請求の回数・額、損害金 < 未請求額、中間利息控除額、未回収元本との近さ）、未来日・開始前の拒否、汎用の状態変更で解約できないこと |
| `testFinanceTermination` | 解約日までの請求、以降の請求の拒否、精算仕訳（128700 借方＝未戻入額、貸借一致、解約損益＝損害金＋引揚物件の帳簿価額 − 正味投資額）、損害金の請求書（明細 1 件・消費税なし・128500 貸方）、冪等、引揚タスク、延滞に損害金（元本）、未請求の回は延滞にならない、月次バッチの対象外、全額入金で未回収元本 0、返還で契約・共通台帳とも完了、帳簿価額 0 の物件の売却益 |
| `testOperatingTermination` | 損害金を 404200 に計上、精算仕訳なし、解約日までの償却 3 か月、保守計画の終了と以降の予定の取消、延滞は収益のバケット、返還時の帳簿価額＝取得価額 − 累計額 |
| `testTerminationDuringReLease` | 損害金 0・請求書なし、前受再リース料の残りの一括計上（224700 → 404100）、期間の完了、未開始の期間の取消、前受残高 0 |

### 16.8 検証

| 検証項目 | 結果 |
|----------|------|
| XML の整形式 | 変更したファイルで OK |
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 1 テーブル（`LEASE_TERMINATION`）、356 行を変更 |
| `gradlew "ofbiz --test component=finance"` | tests=182 / errors=0 / failures=0（L14 の 4 件を含む、41.6 分） |
| demo 投入 | BUILD SUCCESSFUL |
| 画面 | 未確認（定義のみ） |

**入金サービスのトランザクション時間**：DB 作り直し直後でも全体テストが 58〜83 分かかり、`receiveLeasePayment` / `receiveLoanPayment`（core の入金・消込・仕訳を連続して呼ぶ）が既定の 60 秒でタイムアウトする失敗が L7・L6・ローン・L14 の既存テストで出た。core の会計サービスそのものが 1 回 1〜3 秒かかっており（`setInvoiceStatus`・`createAcctgTransAndEntries`・`getParentOrganizations` 等）、環境（Derby・PC の負荷）の要因と見ている。2 つのサービスに `transaction-timeout="600"` を設定して回避した（ローン層の `services_step3.xml` も変更）。本番 DB では通常 60 秒以内に収まる見込みだが、性能は要確認。

### 16.9 要確認・制約

- **損害金の算定方式**：現在価値方式（計算利子率）を採用した。契約書の規定損害金表（回ごとの固定額）を使う運用の場合は、表を持たせる方式を追加する（要確認）。消費者契約では損害金の上限（平均的損害）の確認が必要。
- **消費税**：損害金は不課税とした（税理士の最終確認が必要）。解約日までに請求済みの回の消費税はそのまま。開始時一括（L5）の契約では、開始時に計上した未経過分の消費税の取扱い（調整）を行っていない（要確認）。
- **構成要素**：未請求の構成要素（固定資産税相当額・保守料等）は損害金に含めない（役務・負担が発生しないため）。
- **オペレーティングの償却**：解約月の途中までの償却（日割り）はしない。解約日までに終わった月まで。
- **与信額**：解約後も `FinanceContract` は有効のため、与信の合算に元の取得価額が含まれる（回収完了で外れる）。
- **物件の評価**：引き揚げた物件は残価（または帳簿価額）のまま。処分見込額での評価減はしない。
- **部分解約**：契約の一部の物件だけの解約は対象外（将来拡張「物件分割・契約分割」）。

---

## 17. STEP L15 — 条件変更

有効なリースの期間・月額リース料・計算利子率を、未請求の最初の回から変更する。ローン STEP 7 と同じく、
確定リース料表を新しい revision として作り直し、請求済みの回はそのまま写す。変更前後の値は履歴（`LeaseModification`）に残す。

実装：`LeaseModificationServices`。`LeaseDepreciationServices`（変更後の償却の配分）に連動を追加した。

### 17.1 決定事項（2026-10-15）

| 項目 | 決定 |
|------|------|
| 変更できる項目 | **期間（延長・短縮）・月額リース料・計算利子率**。月額を指定すれば計算利子率を逆算し、指定しなければ計算利子率（既定は現行）で残りの月額を再計算する。見積残価は変えない |
| ファイナンスリースの会計 | **投資資産と繰延リース利益を調整**：元本の回収額は変わらないので、未請求リース料の増減＝利息相当額の増減。増えたら 借 リース投資資産／リース債権 ／ 貸 繰延リース利益、減ったら逆。損益は出ない。以降の戻入は新しいリース料表に従う |
| 開始時一括の消費税 | **総額が変わる変更は不可**（`LEASE_TAX_AT_START` の契約）。総額が変わらない変更は認め、残りの回の消費税の合計を変更前と同じに保つ |
| 適用開始の回 | **未請求の最初の回から**。請求済みの回は変えない |

### 17.2 計算（`plan`）

- k＝未請求の最初の回。k−1 回目の後の未回収元本（k=1 なら取得価額）から残価までを、新しい回数 m＝新期間 − k + 1 で回収する。
- 前払い（`LEASE_PAY_ADVANCE`）では、k 回目の利息相当額は k−1 期に旧条件で発生済みのため、そのまま残し（計算の基礎に加え）、新条件は k 期から適用する。後払いでは k−1 回目の後の元本を基礎にする。
- 計算は L2 の `LeaseCalculator`（月額指定＝料率方式で利子率を逆算、利子率指定＝年金方式）。最終回で端数を調整し、残りの元本は残価で終わる。
- 期日・期間は検収時（L3）と同じ規則（開始日から月ごと、営業日調整）。リース終了日＝開始日＋新期間 − 1 日。
- 新期間は k 以上、商品の最短・最長期間の範囲内。再リースのある契約、全回請求済みの契約は変更できない。
- 構成要素（L9）：請求済みの回は写し、新しい回は定義の月額（元の回にあった額）で作り直す。消費税は契約に固定した税率。

### 17.3 適用（`createLeaseModification`）

1. オペレーティングリース：適用開始の前の月まで償却を済ませ、その月数を `depreciationBaseMonth` に記録する。以降は、残りの償却対象額を新しい残り月数で定額に配分する（`LeaseDepreciationServices.cumulativeFromBase`、最終月で残価に一致）。仕訳はない。
2. 確定リース料表の新 revision（1..k−1 を写し、k..新期間を作り直す）と構成要素の行を作る。
3. ファイナンスリース：利息相当額の増減で 借 リース投資資産 ／ 貸 繰延リース利益（減少は逆）。
4. 履歴（変更前後の期間・月額・利子率・終了日、未請求リース料の前後、利息相当額の増減、revision、仕訳）を残し、契約（期間・終了日・月額・料率・利子率・リース料総額・利息相当額総額・消費税総額）を更新する。
5. 終了日が変わったら、旧終了日の満了通知タスクを取り消す（満了バッチが新しい終了日で作り直す）。

`calculateLeaseModification` は同じ計算を変更なしで返す（試算）。

### 17.4 データモデル

| エンティティ | 主キー | 内容 |
|--------------|--------|------|
| `LeaseModification` | leaseModificationId | 契約、変更日、適用開始の回・日、revision（前・後）、期間・月額・計算利子率・終了日（前・後）、回収する元本、未請求リース料（前・後）、利息相当額の増減、仕訳、償却の基準月（オペレーティング）、理由 |

### 17.5 画面

- `ManageLeaseAgreement` に「条件変更」のスクリーンレットを追加：試算フォーム（変更後の期間・月額・利子率）、試算結果、変更フォーム、変更履歴。有効で再リースのない契約で表示する。

### 17.6 テスト（`LeaseModificationTests`）

| テスト | 内容 |
|--------|------|
| `testFinanceTermExtension` | 変更なし・最長期間超過・請求済みの回より短い期間の拒否、試算は変更しない、48 → 60 か月：新 revision、請求済み 4 回は不変、回収元本が同じ、前払いの 5 回目は旧利息のまま、残価で終わる、終了日、総額＝表の合計、総額の増減＝利息相当額の増減、仕訳（借 128500 ／ 貸 128700）、履歴、戻入・回収状況 |
| `testAmountChangeSolvesRate` | 月額を 100 下げる：計算利子率を逆算（3% 未満）、利息相当額の減少と逆仕訳（借 128700 ／ 貸 128500）、期間・終了日は不変 |
| `testOperatingTermChangeDepreciation` | 60 → 48 か月：仕訳なし、償却の基準月 3、変更後の月の償却額＝残りの償却対象額 / 45、配分の最終月で償却対象額に一致 |
| `testModificationRestrictions` | 開始時一括の消費税の契約で総額が変わる変更を拒否、再リースのある契約を拒否 |

### 17.7 検証

| 検証項目 | 結果 |
|----------|------|
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 1 テーブル（`LEASE_MODIFICATION`） |
| `gradlew "ofbiz --test component=finance"` | tests=186 / errors=0 / failures=0（L15 の 4 件を含む、25 分） |
| demo 投入 | BUILD SUCCESSFUL |
| 画面 | 未確認（定義のみ） |

### 17.8 要確認・制約

- **会計基準**：変更を「同じ元本を新しい条件で回収する」ものとして扱い、利息相当額の増減を繰延リース利益で調整した（損益なし）。リース会計上、条件変更をリースの変更（新しいリース）として扱うべき場合（期間の大幅な延長、区分の再判定）の扱いは要確認。
- **区分の再判定**：変更後にファイナンス／オペレーティングの区分を見直すことはしない。
- **残価・物件**：見積残価、物件の追加・一部返還は変更できない（将来拡張「物件分割・契約分割」）。
- **再リース・解約後**：再リースのある契約、解約後の契約は変更できない。
- **公租公課・保守**：期間変更に合わせた償却資産税の見込み額（構成要素）の再計算、保守計画の期限の見直しはしない（保守予定はリース終了日まで作るため、延長後は自動的に延びる）。
- **与信額**：変更後も与信の合算は元の取得価額のまま。

---

## 18. STEP L15a — 誤操作の取消（リース）

方針・共通部品は `SPEC_COMMON.md` 11 章。実装：`LeaseReversalServices`。すべて FINANCE_ADMIN だけが実行でき、理由が必須。

### 18.1 検収の取消（`reverseLeaseAcceptance`）

- **条件**：`LEASEAGR_ACTIVE` で検収記録があり、その後に次のどれもないこと：請求、繰延リース利益の戻入、減価償却、入金、再リース、条件変更（取消済みを除く）、保守の予定・実績（取消を除く）、満了（返還・通知）、公租公課の申告、延滞の記録。
- **処理**：
  1. 開始仕訳（第1法・オペレーティングとも）と開始時一括の消費税の仕訳を逆仕訳する。
  2. 検収前のリース料表（検収で作った revision の 1 つ前）を、新しい revision として写す（構成要素の行と定義の合計も戻す）。
  3. 契約を `LEASEAGR_CONTRACTED`、共通台帳を `FINCTR_CONTRACTED` に戻し、開始日・終了日を消す。初回支払日・消費税率・消費税合計は見積の値に戻し、検収時に固定した税の情報を消す。
  4. 物件を「購入済」に戻す。オペレーティングは core `FixedAsset` の残存価額・耐用年数末・償却累計を元に戻す。
  5. 満了通知のタスクを取り消す。
  6. 検収記録（`LeaseAcceptance`）を消し、写しを取消記録に残す。
- **その後**：正しい日付で検収し直せる。

### 18.2 中途解約の取消（`reverseLeaseTermination`）

- **条件**：`LEASEAGR_TERMINATED` で解約記録があり、損害金の請求書が未入金（READY のまま）、解約後の入金・物件の返還・延滞の記録がないこと。
- **処理**：
  1. 損害金の請求書を core で取り消す（core が請求書の仕訳を逆仕訳する）。
  2. リース投資資産の精算仕訳を逆仕訳する。
  3. 再リース中だった場合は、解約日の前受再リース料の一括計上を逆仕訳して期間を `LSREL_ACTIVE` に戻す。解約で取り消した未開始の期間は `LSREL_CONTRACTED` に戻す（解約時に付けた目印のコメントで判定）。
  4. 解約で終了した保守計画を元に戻し、取り消した予定を「予定」に戻す（タスクは作り直す）。
  5. 引揚タスクを取り消す。
  6. 契約を `LEASEAGR_ACTIVE` に戻し、解約記録を消して写しを取消記録に残す。
- **残るもの**：解約日までに請求した回はそのまま残る（解約がなくても請求される回のため）。
- **その後**：解約し直せる。

### 18.3 条件変更の取消（`reverseLeaseModification`）

- **条件**：取消していない最新の条件変更があり、変更後の回の請求、変更後の戻入・償却、再リース・解約がないこと。
- **処理**：調整仕訳を逆仕訳し、変更前のリース料表（`fromRevisionNumber`）を新しい revision として写す。契約の期間・終了日・月額・料率・計算利子率・総額・利息相当額総額・消費税総額を変更前に戻す。終了日が変わっていたら満了通知タスクを取り消す。変更記録に `reversedDate` と取消記録の ID を付ける。
- **複数の変更**：新しいものから順に取り消せる。償却の基準（`LeaseDepreciationServices`）は取り消していない最新の変更を使う。

### 18.4 画面

- `ManageLeaseAgreement` に「取消（管理者）」のスクリーンレットを追加（FINANCE_ADMIN のときだけ表示）：検収・解約・条件変更の取消フォーム（理由が必須）と取消の履歴。

### 18.5 テスト（`LeaseReversalTests`）

| テスト | 内容 |
|--------|------|
| `testReverseAcceptance` | 権限のない利用者・理由なし・汎用の状態変更の拒否、取消（状態・共通台帳・物件・リース料表の新 revision が検収前と一致、写し、逆仕訳が開始仕訳の借貸逆）、二重取消の拒否、正しい日付で検収し直し、請求後の取消の拒否 |
| `testReverseTermination` | 権限の拒否、取消（ACTIVE、解約記録なし、損害金の請求書の取消、精算仕訳の逆仕訳、引揚タスクの取消、請求済みの回は残る、損害金 0）、解約し直し、損害金の入金後の取消の拒否 |
| `testReverseModification` | 権限の拒否、取消（リース料表・期間・終了日・月額・総額が変更前と一致、変更記録に取消日、逆仕訳）、二重取消の拒否、再変更後に戻入があると取消の拒否 |

### 18.6 検証

| 検証項目 | 結果 |
|----------|------|
| XML の整形式 | 変更したすべてのファイルで OK |
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | 新規 1 テーブル（`FINANCE_REVERSAL`）、`LOAN_PREPAYMENT` / `LOAN_MODIFICATION` / `LEASE_MODIFICATION` に列を追加、370 行を変更 |
| `gradlew "ofbiz --test component=finance"` | tests=192 / errors=0 / failures=0（21 分、`cleanAll loadAll` で DB を作り直した後）。作り直す前は、途中で止めた全体テストの残存データにより `LoanLifecycleTests.testCreateAgreementWithConfirmedSchedule`・`LoanBusinessLoanTests` の与信 2 件がエラーになった（`SPEC_COMMON.md` 運用メモの既知の症状） |
| demo 投入 | BUILD SUCCESSFUL |
| 画面 | 未確認（定義のみ） |

---

## 19. STEP L15c — 前受リース料と保証金（計画）

> **状態：計画（未着手）**。2026-10-16 に方針を決定した。実装後に「実装」「テスト」「検証」を追記する。

契約時・リース開始前に受け取る前受リース料と、保証金（敷金）を管理する（`REQUIREMENTS_LEASE.md` 将来拡張「前受金」）。

### 19.1 決定事項（2026-10-16）

| 項目 | 決定 |
|------|------|
| 対象 | **前受リース料**と**保証金**。頭金をリース料の計算（取得価額からの控除・計算利子率）に反映することは対象外 |
| 前受リース料の充当 | リース料を請求したときに、古い前受金から順に**自動で請求書へ消し込む** |
| 保証金の精算 | 返還（満了）・解約の後に**精算サービスで、未払の請求書へ古い順に充当し、残りを返還する**。保証金が精算されるまで契約は完了にしない |

### 19.2 データモデル・seed（予定）

| 種類 | 内容 |
|------|------|
| `LeaseAdvanceReceipt`（新規） | 契約、種類（前受リース料／保証金）、受領日、金額、core の入金（Payment）、充当済額、返還額、返還の支払、状態（受領・一部充当・充当済・返還済） |
| GL 科目タイプ（新規） | 前受リース料（流動負債）、預り保証金（固定負債） |
| 入金の種類 | core の `CUSTOMER_DEPOSIT` を使い、入金ごとに貸方の科目（前受リース料／預り保証金）を指定する |

### 19.3 処理（予定）

| サービス | 内容 |
|----------|------|
| 前受金の受領 | 契約済（`LEASEAGR_CONTRACTED`）または有効な契約で受け取る。core の入金で受け、仕訳は 借 現預金 ／ 貸 前受リース料（保証金は 預り保証金） |
| 自動充当（請求時） | `createLeaseInstallmentInvoice` の後に、前受リース料の残高を古い順に請求書へ消し込む（core の消込。仕訳は 借 前受リース料 ／ 貸 売掛金）。請求額を超える分は次の回に回す。再リース・損害金の請求書にも充当する |
| 保証金の精算 | 返還または解約の後に実行する。未払の請求書へ古い順に充当し、残りを core の支払で借手に返す（借 預り保証金 ／ 貸 現預金）。充当・返還を記録して状態を返還済にする |
| 完了の条件 | 今の完了の条件（物件の返還とすべての請求書の決済）に、「保証金が精算済み」を加える |

### 19.4 照会・画面（予定）

- 回収状況（`computeBalance`）に、前受リース料の残高・保証金の残高・充当額を加える。
- 契約画面に、前受金の受領フォーム、保証金の精算フォーム、前受金の一覧を追加する。

### 19.5 テスト（予定）

- 前受金の受領と仕訳、請求時の自動消込（仕訳、回収状況、未回収元本の減少）。
- 請求額を超える前受金が次の回に回ること。
- 保証金の受領、精算（未払への充当と残額の返還）、精算前は契約を完了できないこと。
- 解約後の損害金への充当。

### 19.6 要確認・制約

- **返還の仕訳**：core の支払で保証金を返すときに 借 預り保証金 ／ 貸 現預金 となるか（返金の支払の種類と科目の指定方法）を実装の前に確認する。
- **前受金の利息**：前受金・保証金に利息は付けない。
- **延滞との関係**：前受金で充当した分は延滞にならない。保証金は精算まで延滞の計算に使わない。
- **誤操作の取消**（L15a）：前受金の受領・精算の取消は対象外。

---

## 20. STEP L15d — 物件分割・契約分割（計画）

> **状態：計画（未着手）**。2026-10-16 に方針を決定した。実装後に「実装」「テスト」「検証」を追記する。

1 契約に紐付く物件を分けて扱えるようにする（`REQUIREMENTS_LEASE.md` 将来拡張「物件分割・契約分割」）。分割した後の契約には、中途解約（L14）・返還・条件変更をそのまま使える（部分解約は、分割してから解約することで行う）。

### 20.1 決定事項（2026-10-16）

| 項目 | 決定 |
|------|------|
| 対象 | **物件分割**と**契約分割**。部分解約・部分返還の専用機能は作らない（分割＋既存の解約・返還で行う） |
| 契約分割の時期 | **検収後（有効な契約）だけ**。未請求の最初の回から分割し、請求済みの回・未払の請求書は元の契約に残る |
| 新しい契約の借手 | **元の契約と同じ借手だけ**。別の借手への分割は契約の譲渡にあたり、審査・与信が必要なため対象外 |
| 元の契約 | **すべての物件を新しい契約へ移し、元の契約は終了**（新しい状態「分割済」で請求を止め、請求済みの回の回収が終わったら完了） |

### 20.2 物件分割（予定、`splitLeaseAgreementAsset`）

- 対象は貸出中の、数量 N の物件明細。数量の内訳を指定して複数の明細に分ける。
- 取得価額は数量の比で按分し、端数は最後の明細で調整する（通貨別端数処理）。
- core の `FixedAsset` は新しい明細の分を作り、元の物件の取得価額を減らす。
- オペレーティングリースの減価償却の記録（`LeaseAssetDepreciation`）も同じ比で分ける。
- 契約単位の勘定科目は変わらないため仕訳はない。
- 保守計画・公租公課の区分は元の明細に残す。

### 20.3 契約分割（予定、`splitLeaseAgreement`）

- 条件：有効な契約で、再リース・解約・取り消していない条件変更がないこと。
- 分割の前に、分割日までに期日の来た回を請求し、繰延リース利益の戻入・減価償却を分割日まで済ませる。
- **新しい契約**（物件のグループごと）：
  - 新しい共通台帳（`FinanceContract`）を作る。元の契約との関係は分割履歴に残す。
  - 確定リース料表（revision 1）は、元の表の未請求の最初の回から最後までを取得価額の比で按分して作る。回の番号と期日は元の表のまま。端数は最後の契約で調整する。
  - 構成要素の行、検収記録の写しを作り、状態は有効。
  - 残価、未戻入の繰延リース利益、減価償却の累計を引き継ぐ。
  - 物件の明細、保守計画、公租公課の区分を移す。
- **元の契約**：新しい状態「分割済」にして請求を止める。請求済みの回の入金・延滞は元の契約で続け、回収が終わったら完了にする。与信額の二重計上を避けるため、元の共通台帳の元本は未回収の請求分だけにする。
- **仕訳**：リース投資資産と繰延リース利益を、元の契約から新しい契約へ振り替える（借手が同じため科目ごとの合計は変わらない。記録のための仕訳）。
- **分割履歴**：`LeaseAgreementSplit`（元の契約、新しい契約、按分の比率、分割日、仕訳）。

### 20.4 状態（予定）

- `LEASEAGR_SPLIT`（分割済）を追加：`LEASEAGR_ACTIVE` → `LEASEAGR_SPLIT`（契約分割のサービスだけ）→ `LEASEAGR_COMPLETED`（請求済みの回の回収が終わったとき）。汎用の状態サービスでは使えないようにする。

### 20.5 テスト（予定）

- 物件分割：取得価額・残価・償却の記録の按分と合計の一致。
- 契約分割：新しい契約の表の合計が元の未請求分と一致（端数の調整を含む）、繰延リース利益の引き継ぎ、仕訳、元の契約は請求できず回収はでき完了になること、与信額。
- 分割後の契約の一方だけを解約できること。

### 20.6 要確認・制約

- **按分の基準**：取得価額の比を基本とする。物件ごとに残価が違う場合の按分（残価は物件ごとの値を使うか）は実装時に決める。
- **分割の取消**：対象外（誤操作の取消（L15a）の対象にも含めない）。
- **検収前の分割**：対象外。
- **前受金・保証金**（L15c）：分割した契約への前受金・保証金の振り分けは L15c と合わせて決める。

---

## 運用メモ

- **seed の投入**：L1 で `FinanceLeaseTypeData.xml` を追加した。既存環境では `gradlew "ofbiz --load-data readers=seed --load-data component=finance"` を実行してからテスト・画面操作を行う（未投入だとリース商品種別・申込状態がなく失敗する）。
- **demo**：`FinanceLeaseDemoData.xml`（事務機器ファイナンスリース・所有権移転外 `DEMO_LEASEPROD1`、借手 `DEMO_LESSEE1`、販売店 `DEMO_LS_SUPPLIER1`、受付中の申込 `DEMO_LEASEAPP1` と希望物件 2 件）。
- **L2 の画面確認で作成したデータ**：開発 DB に借手 DEMO_LESSEE1 のリース申込 10030、見積 10010、契約 10010（FinanceContract 10230、CONTRACTED）が残っている。demo の再投入では消えない。不要なら画面から契約を取り消す。
- **見積・契約の日付**：L2 の期日は開始予定日からの暫定値。リース開始日（検収日）で確定するのは L3。
- **外貨建てリースの換算（既知の問題・core）**：契約通貨と貸手組織の基準通貨が違う場合（demo の JPY 契約と、基準通貨 USD の `Company`）、finance 側の仕訳は `convertUom`（JPY→USD 0.006666666667）で換算する。一方、core の支払消込仕訳（`createAcctgTransAndEntriesForPaymentApplication`）は換算率を小数 4 桁（0.0067）に丸めるため、1,500,000 JPY が 10,050 USD で計上され、買掛金・未消込買掛金に 50 USD の差が残った（契約 `10010` の取引 `11042`）。各取引の貸借は一致している。契約通貨と基準通貨が同じ本番構成（JPY の貸手）では発生しない。core は変更していない。外貨建てリースを扱う場合は L4 以降で対応を検討する。
- **開発 DB の再構築（2026-10-13、L8）**：テスト時間の増加（L6 7.4 分 → L7 22.6 分 → L8 48 分）のため `gradlew cleanAll loadAll` で開発 DB を作り直した。L2・L3 の画面確認で作ったデータ（デモ契約 `10010` 等）は消えている。再構築後のテストは 9.2 分。テストを繰り返すと再び遅くなるため、目安として 20 分を超えたら再構築を検討する。
- **テスト時間の増加（L7 時点）**：テストは既定 DB を共有し、実行のたびに契約・請求・仕訳が残る。L7 のテストは全体で 22.6 分かかった（L6 では 7.4 分）。各サービスの処理時間が全体的に伸びており、DB の肥大が主因と見られる。短縮するには、開発用 DB を作り直す（`gradlew cleanAll loadAll` 等。開発 DB の全データが消えるため、実施前に確認すること）か、テスト専用の DB（別 delegator）を用意する。
