# Apache OFBiz Finance LEASE

Apache OFBiz をベースにした独立ファイナンスモジュール（`plugins/finance`）に、ローン業務（STEP 1〜10）に続いて
**リース業務** を追加する。*The Data Model Resource Book* の **Financial Services** のデータモデルを拡張し、
リース会社（貸手）としての業務を提供する。

ローン業務の要件は `REQUIREMENTS.md`、ローン層の詳細仕様・運用記録は `SPEC.md` を正とする。
本書は共通層の切り出し（STEP L0）とリース業務の要件を定める。詳細仕様・運用記録は、共通層を `SPEC_COMMON.md`、
リース層を `SPEC_LEASE.md` に記録する。

---

## 1. 全体方針

### 1.1 確定事項

| 項目 | 決定内容 |
|------|----------|
| 立場 | **貸手（リース会社）側のみ**。借手側の会計処理は扱わない |
| リース区分 | ファイナンスリース（所有権移転・所有権移転外）とオペレーティングリースを扱う。**初期スコープはファイナンスリースのみ** |
| 会計基準 | 新リース会計基準（企業会計基準第34号等、2027年4月1日以後開始事業年度から適用）に準拠する |
| 収益計上方式 | ファイナンスリースは **第1法：リース取引開始日に売上高と売上原価を計上する方法** を主とする（→ 1.4 参照） |
| 消費税 | リース料に課税する |
| 配置 | `plugins/finance` 内に追加する。**ローン商品と並ぶ「商品」として追加** する（`FinancialProductType` の拡張） |
| 構成 | 1 プラグイン内を **共通層・ローン層・リース層** に分ける（→ 1.2） |
| 契約台帳 | ローン・リースを横断する共通契約台帳 **`FinanceContract`** を新設する（→ STEP L0） |
| 申込 | リース専用の申込 **`LeaseApplication`** を持つ。審査は共通層のサービスとする |
| 与信・督促 | 同一顧客のローンとリースを **合算して与信判定** し、**顧客単位で延滞・督促を照会** できるようにする |
| 開発体制 | ローンとリースは同一チーム・同一リリースで開発する |
| 段階導入 | 再リース・中途解約・メンテナンスリース（保守料込み）は初期スコープに含めず、後続 STEP で段階的に追加する |

### 1.2 構成（層）と既存資産の再利用

独立プラグイン `plugins/finance` 内に実装し、OFBiz 本体（`applications/`）は改変しない。
プラグイン内部を次の 3 層に分ける。

| 層 | 役割 | 主な内容 |
|----|------|----------|
| 共通層（common） | ローン・リースが共有する土台 | 共通契約台帳 `FinanceContract`、契約の直列化（`processingLockVersion`）、審査、与信（合算）、延滞・督促・遅延損害金、会計連携部品、利息計算・営業日調整、設定解決、帳票基盤、権限 |
| ローン層（loan） | ローン固有 | ローン申込・見積・契約・確定返済表・繰上返済・条件変更（既存 STEP 1〜10） |
| リース層（lease） | リース固有 | リース申込・見積・契約・確定リース料表・物件・検収・リース会計・消費税・満了処理 |

- **依存の向きは「ローン層 → 共通層」「リース層 → 共通層」のみ**。ローン層とリース層は互いを参照しない。
- ファイルとパッケージを層ごとに分ける。
  - Java：`org.apache.ofbiz.finance.common` / `org.apache.ofbiz.finance.loan` / `org.apache.ofbiz.finance.lease`
  - entitydef：`entitymodel_common*.xml` / 既存の `entitymodel*.xml`（ローン）/ `entitymodel_lease*.xml`
  - servicedef・widget・data も同じ単位（`services_common*.xml`、`services_lease*.xml`、`FinanceLease*Forms.xml` 等）
- 既存のサービス名・リクエスト URI は変更しない（パッケージ移動のみ）。
- core 変更が必要な場合は、理由・影響範囲・代替案・検証結果を、その変更を必要とした層の仕様書（`SPEC_COMMON.md` / `SPEC_LEASE.md`）に記録する（AGENTS.md の Change Boundaries に従う）。
- `AGENTS.md` の Domain Rules / Accounting Rules を共通・ローン・リースに書き分ける（STEP L0）。

| 用途 | 再利用する資産 | 備考 |
|------|----------------|------|
| 借手（顧客）・販売店（サプライヤ） | `party` | 法人/個人 Party。販売店は SUPPLIER ロール |
| リース商品 | `product` + `FinancialProduct`（方法B） | `FinancialProductType` に LEASE 系種別を追加 |
| リース物件 | `accounting` の `FixedAsset` / `FixedAssetType` | 物件はリース会社所有の固定資産として登録 |
| 物件の購入 | `order`（購買発注）/ `Invoice`（仕入請求） | 販売店からの購入・支払 |
| 請求・入金・仕訳 | `Invoice` / `Payment` / `PaymentApplication` / `AcctgTrans` | ローン STEP 3 と同じ方式 |
| 消費税 | `accounting` の tax（`TaxAuthority` / `TaxAuthorityRateProduct`） | リース料請求明細に課税 |
| 減価償却（オペレーティング） | `FixedAssetDepMethod` / `calculateFixedAssetDepreciation` | オペレーティングリース STEP で使用 |
| 審査 | 共通層の審査サービス（既存 `LoanUnderwriting` を L0 で共通化） | 3.1 参照 |
| 与信 | 共通層の与信判定（ローン＋リースの合算、L0） | 既存 `ValidateCreditLimit` を拡張 |
| 利息計算・端数処理 | `FinanceInterestCalculator`（日割り基準・端算入・通貨別丸め） | リース料の利息相当額配分に使用 |
| 営業日調整 | `BusinessDayCalculator` | リース料支払日の調整 |
| 延滞・督促 | ローン STEP 6 の延滞判定・督促 | L0 で共通層へ切り出し、L6 でリースを登録 |
| 帳票 | JasperReports（見積書・契約書と同じ方式） | 3.3 参照 |
| 設定 | `finance.properties` / `SystemProperty` / 商品単位 override | ローン STEP 8〜10 と同じ解決階層 |

### 1.3 リース業務とローン業務の主な違い

| 観点 | ローン | ファイナンスリース（貸手） |
|------|--------|----------------------------|
| 貸し付けるもの | 金銭 | 物件（リース会社が購入して貸す） |
| 所有者 | — | リース会社（所有権移転リースは満了時に借手へ移転） |
| 実行 | 融資実行（FinAccount 開設） | 物件の購入 → 借手の検収（**検収日＝リース開始日**） |
| 回収単位 | 元利金 | リース料（＋消費税） |
| 計算要素 | 元本・金利・期間 | 取得価額・残価・料率（または計算利子率）・期間・前払/後払 |
| 期日前の解約 | 繰上返済 | 原則解約不能（中途解約は解約損害金、後続 STEP） |
| 満了時 | 完済 | 返還／再リース／所有権移転（譲渡） |
| 会計（貸手） | 貸付金・受取利息 | リース債権（所有権移転）/ リース投資資産（移転外）、売上高・売上原価 |

### 1.4 要確認事項（会計）

> **収益計上方式**
> 現行基準（企業会計基準第13号）では、貸手のファイナンスリースに次の3方式が認められている。
> 第1法：リース取引開始日に売上高と売上原価を計上する方法／第2法：リース料受取時に売上高と売上原価を計上する方法／
> 第3法：売上高を計上せず、利息相当額を各期へ配分する方法。
> 新リース会計基準（第34号）では第2法が廃止され、第1法・第3法のみになると理解している。
> 本書は **第1法を主たる方式** として設計する。
>
> 第1法の会計処理（概要）
> - リース開始日：売上高 ＝ リース料総額（税抜）、売上原価 ＝ 物件の取得価額。リース債権／リース投資資産をリース料総額で計上。
> - 売上高と売上原価の差額（利息相当額）のうち、当期に帰属しない部分は **繰延リース利益** として繰り延べる。
> - 各期：利息法で当期に帰属する利息相当額を算定し、繰延リース利益を戻し入れる（繰延リース利益戻入益）。
> - リース料受取時：リース債権／リース投資資産を減額する（売上は計上しない）。
>
> 新基準における第1法の売上高の測定（リース料総額か、その現在価値か）、繰延リース利益の表示科目、
> 残価の扱いの詳細は、実装着手（L4）前に会計監査人・経理部門に確認すること。
>
> 方式の追加に備え、**収益計上方式は Enumeration（`LEASE_REV_METHOD`）で選択可能な設計** とし、仕訳生成を方式ごとの
> 戦略クラスに分離する。第1法（`LEASE_REV_AT_START`）を初期実装とし、第3法（`LEASE_REV_INTEREST_ONLY`）は
> 同じ利息法の計算結果を使って追加できるようにする。

> **注意：消費税の計上時期**
> 消費税法上、ファイナンスリースは原則として **リース開始時（物件引渡時）の資産の譲渡** として扱われ、
> リース料総額に係る消費税を一括で課税売上とするのが原則である。請求は各回のリース料に消費税を付して行うが、
> 課税売上の計上時期（一括／各回）と会計上の仮受消費税の扱いは、L5 で経理部門・税理士に確認の上で確定する。

---

## 2. 用語

| 用語 | 定義 |
|------|------|
| 取得価額 | リース会社が物件を購入した価額（税抜） |
| リース料総額 | 各回リース料（税抜）の合計 |
| 見積残存価額（残価） | 満了時の物件の見積処分価額。所有権移転外では通常 0 または保証残価 |
| 計算利子率 | 取得価額＋見積残存価額の現在価値をリース料総額の現在価値と一致させる利率（貸手の計算利子率） |
| 月額リース料率 | 月額リース料 ÷ 取得価額（リース料算出の簡便法） |
| 利息相当額 | リース料総額 − 取得価額（＋残価調整）。利息法で各期に配分 |
| 元本回収額 | 各回リース料 − 当該期の利息相当額 |
| 検収 | 借手による物件受領・確認。検収日がリース開始日（リース期間の起算日） |
| 前払／後払 | 第1回リース料を開始日に受け取る（前払）か、1期間後に受け取る（後払）か |

---

## 3. 共通設計方針

### 3.1 商品として追加する方式

- `FinancialProductType` に以下を追加する。
  - `LEASE`（親）
    - `LEASE_FINANCE_TRANSFER`（ファイナンスリース・所有権移転）
    - `LEASE_FINANCE_NON_TRANSFER`（ファイナンスリース・所有権移転外）
    - `LEASE_OPERATING`（オペレーティングリース、後続 STEP）
- リース固有の商品属性は `FinancialProductLease`（`productId` で `FinancialProduct` と 1:1）に持たせ、
  ローン用の `FinancialProduct` は変更しない。
- **申込はリース専用の `LeaseApplication` を持つ**。`LoanApplication` は流用しない。
  - リース商品（`FinancialProductType` が LEASE 系）だけを申込対象とする。
  - 希望物件・販売店は `LeaseApplicationAsset`（申込 1:N 希望物件）に持つ。
- **審査は共通層のサービスとする**。L0 で既存の審査（`LoanUnderwriting`）を、申込の種別（ローン／リース）と
  申込 ID を受け取る共通の審査に寄せる。承認・否決時の状態・decision・reviewDate の一貫した確定、
  承認後の変更禁止はローンと同じ規則を適用する。
- 契約はリース専用の `LeaseAgreement` とし、共通契約台帳 `FinanceContract` に 1:1 で登録する（L0）。

### 3.2 業務ルール（ローンと同等の制約）

- 申込は作成時に必ず `LEASEAPP_RECEIVED`。内容の更新は RECEIVED 中だけに限定し、状態変更は専用の状態遷移サービスのみで行う。
- `LeaseQuote` は生成後に不変。条件変更は新規見積を作成する。
- 契約作成には、承認済み申込・受理済み見積・承認済み審査・完全なリース料表が必要。契約作成時にリース料表を
  スナップショットし、同一見積からの契約は 1 件だけとする。
- 請求は見積ではなく **契約確定リース料表（`LeaseAgreementPaymentSchedule`）** だけを参照する。
- entity-auto 等の通常 CRUD で業務 STATUS を変更しない。
- 共通契約台帳 `FinanceContract.processingLockVersion` による条件付き更新で、同一契約の計上・請求・入金を直列化する。
- `data/*TypeData.xml` は seed、`data/*DemoData.xml` は demo、`testdef/data/*` はテスト専用。用途を混在させない。

### 3.3 帳票

見積書・契約書と同じ方式（Groovy イベント → `jrParameters` + `jrDataSource` → JasperReports view handler、
CJK フォント）で作成する。

### 3.4 設定の解決階層

ローン STEP 8〜10 と同じ。`FinancialProductLease` の項目 → `SystemProperty` / `finance.properties` → 既定値。
通貨別端数処理（JPY は小数以下切り捨て）、日割り基準、端算入、営業日調整はローンと共通の設定を使う。

---

## STEP L0 — 共通層の切り出し（リース追加の前提）

> **状態：完了**（tests=50 / errors=0 / failures=0）。実装内容は `SPEC_COMMON.md` を参照。
> 要件からの差分：契約ロックの部品名は `FinanceContractHelper.lock`、共通状態は `FINCTR_*` の 4 状態とした。
> 照会用の未回収元本スナップショット（`outstandingPrincipal`）は持たず、与信合算は有効契約の元本（`principalAmount`）で行う
> （STEP 5 の方式を踏襲。返済による減額はしない）。

リース機能を追加する前に、ローン前提で書かれた処理を共通層へ切り出す。**機能追加は行わず、既存ローン業務の
挙動を変えない**（リファクタリング STEP）。

### 現状の課題（調査結果）

| 箇所 | ローン前提の内容 |
|------|------------------|
| `FinanceAccountingServices` | `LoanAgreement` / `LoanAgreementRepaymentSchedule` / `LoanInstallmentInvoice` を直接参照。`processingLockVersion` の条件付き更新が `LoanAgreement` 固定 |
| `FinanceDelinquencyServices` | 延滞判定・遅延損害金・督促がすべて `LoanAgreement` 前提 |
| `ValidateCreditLimit.groovy` | 与信額が `LOANAGR_ACTIVE` のローン契約元本の合計のみ |
| `LoanUnderwriting` | 審査が `loanApplicationId` 固定 |

### データモデル

- **`FinanceContract`（共通契約台帳）** を新設する。
  - `financeContractId`（PK）、`contractTypeId`（`LOAN` / `LEASE`）、`customerPartyId`、`productId`、
    `statusId`（共通の契約状態：契約済・有効・完了・償却 等）、`currencyUomId`、`contractDate`、
    `outstandingPrincipal`（照会用の未回収元本スナップショット、正本は各層で算定）、`processingLockVersion`。
- `LoanAgreement` に `financeContractId` を追加し、`FinanceContract` と 1:1 で紐付ける。
  - 既存データは移行スクリプト（seed/demo/テスト）で `FinanceContract` を生成して紐付ける。
  - DDL 変更を伴うため DB 再構築が必要（`SPEC_COMMON.md` の運用メモに記録）。
- 延滞・督促・遅延損害金のエンティティ（`LoanDelinquency` / `LoanLateFeeAccrual` / `LoanDunningNotice` /
  `LoanDelinquencyAction`）に `financeContractId` を追加し、共通層から契約種別を問わず参照できるようにする
  （既存の `loanAgreementId` は互換のため残す）。
- 審査（`LoanUnderwriting`）に `applicationTypeId`（`LOAN` / `LEASE`）と汎用の申込 ID を追加する
  （既存の `loanApplicationId` は互換のため残す）。

### 共通層の部品

- **契約ロック**：`FinanceContractLock`（共通ヘルパー）で `FinanceContract.processingLockVersion` の条件付き更新を行う。
  既存のローン処理はこれを経由するよう置き換える。
- **契約種別ごとの差分の吸収**：共通層は契約種別ごとの「契約アダプタ」（インターフェース）を通じて、
  期日到来分の明細・延滞元本／延滞利息（リースは延滞リース料）・請求との対応を取得する。
  ローン層がローン用アダプタを実装し、リース層が L6 でリース用アダプタを実装する。
- **延滞・督促**：延滞判定バッチ・区分判定・遅延損害金計上・督促状を、`FinanceContract` を起点に動くよう変更する。
- **与信（合算）**：顧客の与信使用額 ＝ 有効なローン契約の未回収元本 ＋ 有効なリース契約の未回収元本 ＋ 審査中の申込額
  （既存の計算方式を踏襲）。`FinanceContract` を `customerPartyId` で検索して算定する。
- **顧客単位の照会**：同一顧客のローン・リース契約、延滞状況、督促履歴を一覧する画面を共通層に追加する。

### パッケージ・ファイルの整理

- 既存 Java を `org.apache.ofbiz.finance.common`（`FinanceInterestCalculator` / `BusinessDayCalculator` /
  延滞・督促・ロック・与信）と `org.apache.ofbiz.finance.loan`（会計・繰上返済）に分ける。
- サービス名・リクエスト URI・画面名は変更しない（定義ファイル内の Java クラス参照のみ変更）。
- `AGENTS.md` を共通・ローン・リースのルールに書き分ける。

### テスト

- **既存テスト（現在 48 件）がすべて通ること** を完了条件とする。
- 追加：`FinanceContract` の生成と 1:1 紐付け、ロックの条件付き更新、与信合算（ローンのみのケースで従来値と一致）、
  顧客単位の延滞照会。

---

## STEP L1 — リース商品・物件・申込の基盤

リース商品と物件を定義し、リース申込を受け付けられる状態にする。

> **状態：完了**（tests=63 / errors=0 / failures=0）。実装内容は `SPEC_LEASE.md` 2 章・`SPEC_COMMON.md` 5.1 / 5A を参照。
> 要件からの差分：
> - ID 列が 20 文字以内のため、商品種別は `LEASE_FIN_TRANSFER` / `LEASE_FIN_NONTRANS` / `LEASE_OPERATING`、区分は `LEASE_CLS_*` とした。
> - 期間の上下限は既存の `FinancialProduct.minTermMonths` / `maxTermMonths` を使い、`FinancialProductLease` には持たない。
> - `LEASE_REV_METHOD` は第1法（`LEASE_REV_AT_START`）だけを seed した。第3法は実装時に追加する。オペレーティングリースの条件登録は未対応として拒否する。
> - ローン用の商品画面は存在しなかったため、ローン・リース共通の商品画面（業務・種別で絞込）を新設した。
> - 見積取得価額は希望物件明細の合計額（税抜）とした。

### データモデル

- `FinancialProductType` に LEASE 系種別を追加（seed）。
- `LeaseApplication`：`leaseApplicationId`（PK）、`productId`（リース商品）、`applicantPartyId`、
  `statusId`（`LEASE_APP_STATUS`：`LEASEAPP_RECEIVED` → 審査中 → 承認／否決 → 契約済）、`currencyUomId`、
  `requestedTermMonths`、`applicationDate`、`purpose`、`comments`。状態履歴 `LeaseApplicationStatus`。
- `FinancialProductLease`（`productId` PK、`FinancialProduct` 1:1）
  - `leaseClassEnumId`（FINANCE_TRANSFER / FINANCE_NON_TRANSFER / OPERATING）
  - `minTermMonths` / `maxTermMonths`、`defaultMonthlyRateFactor`（月額リース料率）または `defaultCalcInterestRate`
  - `paymentTimingEnumId`（ADVANCE 前払 / ARREARS 後払）
  - `residualValueRate`（見積残存価額率、移転外は既定 0）
  - `revenueMethodEnumId`（`LEASE_REV_METHOD`：初期は `LEASE_REV_AT_START`＝第1法）
  - `taxable`（課税対象、既定 Y）
- リース物件：core `FixedAsset` を使用（`fixedAssetTypeId` で物件種別、所有者＝リース会社）。
  - `FinancialProductLeaseAssetType`：商品ごとに対象となる物件種別を制限（任意）。
- `LeaseApplicationAsset`（申込 × 希望物件）：`leaseApplicationId`、`seqId`、`fixedAssetTypeId`、物件名、
  数量、見積取得価額、`supplierPartyId`（販売店）。

### サービス

- リース商品属性の CRUD。
- リース申込の CRUD と状態遷移サービス（作成時は必ず `LEASEAPP_RECEIVED`、内容更新は RECEIVED 中のみ）。
- 申込への希望物件の追加・更新・削除（`LEASEAPP_RECEIVED` 中のみ）。
- 希望物件のない申込は審査承認できない検証。
- 審査は L0 の共通審査サービスを `applicationTypeId=LEASE` で利用する。

### 画面

- リース商品の一覧・登録・編集（ローン商品画面に種別フィルタを追加）。
- リース申込の検索一覧（フィルタ付き）・登録編集・管理画面（希望物件、審査）。申込者は氏名と ID を併記。

### テスト

- リース商品 CRUD、リース申込の CRUD と状態遷移、希望物件の登録、RECEIVED 以外での変更拒否、
  物件未登録のリース申込の承認拒否、ローン申込への影響がないこと。
- seed / demo データ（リース商品：所有権移転外の事務機器リース 等）。

---

## STEP L2 — リース見積・審査・契約

### リース料計算

- 入力：取得価額（税抜）、リース期間（月）、残価、前払/後払、月額リース料率 **または** 計算利子率。
- 料率方式：月額リース料 ＝ 取得価額 × 月額リース料率（通貨別丸め）。逆算した計算利子率を保存する。
- 利子率方式：取得価額 −（残価の現在価値）を、計算利子率・期間・前払/後払で年金現価換算して月額リース料を算出。
- 各回について利息法で **利息相当額** と **元本回収額** を算出し、最終回で端数を調整して
  元本回収額の合計 ＝ 取得価額 − 残価 とする。
- 消費税額は各回リース料に税率を乗じて算出（表示用。請求時に `Invoice` の税明細として計上）。

### データモデル

- `LeaseQuote`：`leaseQuoteId`、`leaseApplicationId`、`statusId`（`LEASEQT_*`）、`currencyUomId`、
  `acquisitionCost`、`residualValue`、`termMonths`、`paymentTimingEnumId`、`monthlyLeaseAmount`、
  `monthlyRateFactor`、`calcInterestRate`、`totalLeaseAmount`、`totalInterestAmount`、`taxRate`、`totalTaxAmount`、
  `firstPaymentDate`、`createdDate`。
- `LeaseQuoteAsset`：見積時点の物件明細（申込の希望物件から確定）。
- `LeasePaymentSchedule`（見積リース料表）：`leaseQuoteId` + `installmentNumber`、`dueDate`、
  `periodFromDate` / `periodThruDate`、`leaseAmount`、`principalPortion`、`interestPortion`、`taxAmount`、
  `remainingPrincipal`。
- `LeaseAgreement`：`leaseAgreementId`、`financeContractId`（`FinanceContract` 1:1、`contractTypeId=LEASE`）、
  `leaseApplicationId`、`leaseQuoteId`（UNIQUE）、`statusId`（`LEASEAGR_*`）、
  `lesseePartyId`、`supplierPartyId`、`leaseClassEnumId`、`revenueMethodEnumId`、条件のスナップショット、
  `agreementDate`、`leaseStartDate`（検収日、L3 で確定）、`leaseEndDate`。
  直列化のロックは `FinanceContract.processingLockVersion` を使う。
- `LeaseAgreementPaymentSchedule`（契約確定リース料表）：`leaseAgreementId` + `scheduleRevisionNumber` +
  `installmentNumber`。項目は見積リース料表と同じ。
- `LeaseAgreementAsset`：契約 × `FixedAsset`（物件の紐付け）。

### サービス

- `createLeaseQuote`（リース料計算と見積リース料表の生成）。見積の状態遷移（作成 → 提示 → 受理／否決）。
- 審査は共通層の審査サービスを `applicationTypeId=LEASE` で利用する（承認額 ＝ 取得価額）。
- `createLeaseAgreement`：承認済み申込・受理済み見積・承認済み審査・完全なリース料表を検証し、契約と
  確定リース料表（revision 1）をスナップショット。同時に `FinanceContract`（`contractTypeId=LEASE`）を作成する。
  状態は `LEASEAGR_CONTRACTED`（検収待ち）。
- 与信チェックは L0 の共通与信判定を使い、同一顧客のローン未回収元本とリース未回収元本を合算して検証する
  （審査承認時・契約作成時）。

### 画面・帳票

- リース見積の作成・一覧・詳細（リース料表 8〜9 列）、リース見積書 PDF。
- リース契約の一覧（フィルタ付き）・詳細、リース契約書 PDF。

### テスト

- 料率方式・利子率方式のリース料計算、前払/後払、残価あり/なし、端数調整（元本回収額の合計一致）。
- 見積の不変性、同一見積からの二重契約拒否、条件未充足での契約拒否。

---

## STEP L3 — 物件の購入・検収・リース開始

### 業務フロー

1. 契約後、販売店へ物件を発注（core の購買発注を任意で利用。最小構成では購入記録のみ）。
2. 販売店からの請求（仕入 `Invoice`）を登録・支払い、`FixedAsset` を作成（取得価額、取得日、所有者＝リース会社）。
3. 借手が物件を検収 → **検収日をリース開始日として確定**。リース料表の期日を検収日基準で確定し直す
   （新 revision、または契約時スナップショットの確定処理）。
4. 契約を `LEASEAGR_ACTIVE` に遷移。

### データモデル

- `LeaseAcceptance`（検収記録）：`leaseAgreementId`、`acceptanceDate`、`acceptedByPartyId`、備考、
  物件受領書の出力記録。
- `FixedAsset` との紐付け（`LeaseAgreementAsset.fixedAssetId`）。物件状態は FixedAsset の状態または
  リース側の状態（発注済 → 検収済 → リース中 → 返還済／譲渡済）で管理。

### 会計

- 物件取得：借 リース物件（仮勘定）／ 貸 買掛金（取得価額）。支払時：借 買掛金 ／ 貸 現預金。
- リース開始（検収日、第1法）
  - 借 リース投資資産（移転外）またはリース債権（移転）／ 貸 リース売上高（リース料総額・税抜）
  - 借 リース売上原価 ／ 貸 リース物件（仮勘定）（取得価額）
  - 借 繰延リース利益繰入（売上総利益の控除）／ 貸 繰延リース利益（利息相当額の全額）
- 仕訳は既存の `AcctgTrans` 連携（GL 科目は `GlAccountTypeDefault` にリース用科目タイプを seed で追加）。
- 開始仕訳は契約ごとに 1 回だけ生成し、再実行時は既存仕訳を返す（冪等）。

### テスト

- 検収前の請求拒否、検収日によるリース料表期日の確定、物件と契約の紐付け、物件取得仕訳・開始仕訳の貸借一致、
  売上高 − 売上原価 ＝ 繰延リース利益（開始時点）。

---

## STEP L4 — 月次会計・リース料請求・入金消込

### 月次バッチ

ローン STEP 3 と同じく、期日順に処理し二重計上しない。

1. 期日到来分のリース料を請求：`Invoice` を作成して READY 化（明細：リース料・消費税）。
   - 借 売掛金（リース料＋消費税）／ 貸 リース投資資産（リース料）・仮受消費税
   - 売上は開始時に計上済みのため、請求時には計上しない。
2. 繰延リース利益の戻入（第1法）
   - 利息法で当期に帰属する利息相当額を算定し、借 繰延リース利益 ／ 貸 繰延リース利益戻入益。
   - 月中再実行は直前計上期間の翌日から増分計上し、二重計上しない（ローン STEP 3 と同じ方式）。
   - 戻入額の累計は、契約期間満了時に開始時の繰延リース利益と一致させる（最終回で端数調整）。
3. 方式は `revenueMethodEnumId` で切り替える戦略クラスに分離する（第3法の追加に備える）。

### 入金消込

- `Payment`（`PMNT_RECEIVED` / `PMNT_CONFIRMED`）と `PaymentApplication` を正本とする。
- 消込の充当順序：消費税 → リース料（必要に応じて遅延損害金を先行。ローン STEP 6 と整合）。
- 未回収元本は、有効な消込と確定リース料表の元本回収額から算定する。請求しただけでは減額しない。

### テスト

- 月次バッチの冪等性、期日順処理、請求額 ＝ 確定リース料表、GL の貸借一致、
  繰延リース利益戻入の累計 ＝ 開始時の繰延リース利益（満了時）、月中再実行での二重計上なし、
  一部入金・消込解除（core 修正済みの `removePaymentApplication` を利用）。

---

## STEP L5 — 消費税

- リース料請求明細に消費税を付す（`Invoice` の税明細、`TaxAuthorityRateProduct` でリース商品に税率設定）。
- 税率は請求日ではなく **契約（リース開始）時点の税率で固定** する（経過措置を考慮した設計とする）。
- 課税売上の計上時期（開始時一括／各回）を設定で選択可能にする。1.4 の確認結果に従い既定値を決める。
- 通貨別端数処理（JPY：切り捨て）を消費税額にも適用し、明細単位と合計の端数差の扱いを定める。

### テスト

- 税額計算（端数処理）、税率改定をまたぐ契約での税率固定、請求書の税明細と GL（仮受消費税）の一致。

---

## STEP L6 — 延滞・督促

- L0 で共通層へ切り出した延滞判定・区分・遅延損害金・督促状・償却/法的手続きに、**リース用の契約アダプタを登録する**
  （延滞リース料・期日到来明細・請求との対応を返す）。
- 延滞判定バッチはローン・リースを `FinanceContract` 単位で一括処理する。
- 遅延損害金の計算基礎はリース料（税込/税抜は設定で選択）。
- 督促状 PDF はリース用の文言で出力する。顧客単位の督促照会（L0）にリース契約が表示されること。

### テスト

- リース契約の延滞検出・区分、遅延損害金の GL 計上、督促通知、同一顧客のローン・リース延滞の合算照会。
  ローンの既存テストが回帰しないこと。

---

## STEP L7 — 期間満了処理（返還・所有権移転）

初期スコープの満了処理のみ扱う（再リースは後続 STEP）。

- 所有権移転外：満了時に物件の返還を記録し、契約を `LEASEAGR_COMPLETED` に遷移。
  物件の処分（売却・廃棄）を記録し、残価と処分価額の差額を損益計上。
- 所有権移転：最終回入金後に所有権移転（譲渡）を記録し、`FixedAsset` の所有者・状態を更新。
- 満了予定一覧（N か月以内に満了する契約）を提供し、借手への満了通知書を出力する。

### テスト

- 満了前の完了処理拒否、返還・譲渡の記録、物件状態の更新、処分損益の仕訳。

---

## STEP L8 — 帳票・照会

- リース見積書、リース契約書（L2）、物件受領書（検収書、L3）、満了通知書（L7）の PDF。
- 契約別リース料回収状況、未回収元本残高一覧、物件台帳（物件 × 契約 × 状態）の照会画面。

---

## 後続 STEP（初期スコープ外、段階的に追加）

| STEP | 内容 |
|------|------|
| L9 | 再リース：満了時の再リース契約（年額再リース料、1 年単位の更新）、再リース料の収益計上 |
| L10 | 中途解約：解約損害金（未経過リース料 − 中途解約による利息相当額控除 等）の算定と請求、物件引揚 |
| L11 | メンテナンスリース：保守料込みリース料の区分（リース料部分とサービス部分の分離・別収益計上）、保守契約・保守実績の管理（`FixedAssetMaint`） |
| L12 | オペレーティングリース：物件を固定資産計上して減価償却（`calculateFixedAssetDepreciation`）、受取リース料を収益計上 |
| L13 | 条件変更：リース期間・リース料の変更（リース料表の再作成、ローン STEP 7 の改訂方式を流用） |

---

## 将来拡張

### 前受金

- 契約時・リース開始前に受け取る前受リース料（第1回前払分、頭金等）と保証金（敷金）の管理。
- 前受金の受領：借 現預金 ／ 貸 前受金（前受リース料）・預り保証金。
- リース料請求時に前受金を充当（`PaymentApplication` による消込、充当順序の設定）。
- 保証金は満了・解約時に精算（未収リース料・損害金との相殺、残額返還）。
- 前受金残高の照会と、前受金を考慮したリース料計算（計算利子率への反映）。

### 物件分割・契約分割

中途解約・物件の部分返還と関連する機能として、1 契約に紐付く複数物件を分けて扱えるようにする。

- **物件分割**：1 件の `FixedAsset`（数量 N の一括物件）を複数の物件に分割し、取得価額・残価・
  リース料・未回収元本・繰延リース利益を按分する（按分基準：取得価額比 等）。
- **契約分割**：1 契約を複数契約に分割する（物件単位・借手の事業所単位など）。元契約のリース料表を
  按分して新契約の確定リース料表（revision 1）を作成し、元契約は分割済みとして終了する。分割履歴を保持する。
- **部分返還・部分解約**：分割した物件・契約単位で返還・中途解約（L10）を適用する。
- 按分時の端数は通貨別端数処理に従い、合計が元の金額と一致するよう最終明細で調整する。
- 分割時点の会計（リース投資資産・繰延リース利益の振替）を仕訳として記録する。

### その他

- 祝日カレンダーを考慮した営業日調整（ローンと共通）。
- 複数物件の一括契約。
- 販売店向けの販売支援（リース料試算の外部提供）。
- 第3法（利息相当額の配分）の収益計上方式の追加。
