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

## 運用メモ

- **seed の投入**：L1 で `FinanceLeaseTypeData.xml` を追加した。既存環境では `gradlew "ofbiz --load-data readers=seed --load-data component=finance"` を実行してからテスト・画面操作を行う（未投入だとリース商品種別・申込状態がなく失敗する）。
- **demo**：`FinanceLeaseDemoData.xml`（事務機器ファイナンスリース・所有権移転外 `DEMO_LEASEPROD1`、借手 `DEMO_LESSEE1`、販売店 `DEMO_LS_SUPPLIER1`、受付中の申込 `DEMO_LEASEAPP1` と希望物件 2 件）。
- **L2 の画面確認で作成したデータ**：開発 DB に借手 DEMO_LESSEE1 のリース申込 10030、見積 10010、契約 10010（FinanceContract 10230、CONTRACTED）が残っている。demo の再投入では消えない。不要なら画面から契約を取り消す。
- **見積・契約の日付**：L2 の期日は開始予定日からの暫定値。リース開始日（検収日）で確定するのは L3。
- **外貨建てリースの換算（既知の問題・core）**：契約通貨と貸手組織の基準通貨が違う場合（demo の JPY 契約と、基準通貨 USD の `Company`）、finance 側の仕訳は `convertUom`（JPY→USD 0.006666666667）で換算する。一方、core の支払消込仕訳（`createAcctgTransAndEntriesForPaymentApplication`）は換算率を小数 4 桁（0.0067）に丸めるため、1,500,000 JPY が 10,050 USD で計上され、買掛金・未消込買掛金に 50 USD の差が残った（契約 `10010` の取引 `11042`）。各取引の貸借は一致している。契約通貨と基準通貨が同じ本番構成（JPY の貸手）では発生しない。core は変更していない。外貨建てリースを扱う場合は L4 以降で対応を検討する。
- **L3 の画面確認で検収したデモ契約**：開発 DB のデモ契約 `10010` は検収済み（ACTIVE、開始日 2026-10-12）。demo の再投入では元に戻らない。
