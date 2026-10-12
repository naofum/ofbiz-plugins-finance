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
| L0 | 共通層の切り出し（`FinanceContract`、契約ロック、審査・与信・延滞の共通化、パッケージ整理、顧客照会） | 完了（tests=50 / errors=0 / failures=0） |
| L1 | 申込種別に依存しない審査作成・承認検証の登録、共通の金融商品画面（リース層側は `SPEC_LEASE.md`） | 完了（tests=63 / errors=0 / failures=0） |
| L3 | 共通の GL 計上部品 `FinanceGlHelper`（リース層側は `SPEC_LEASE.md` 4 章） | 完了（testcase 44 件、errors=0 / failures=0） |
| L2 | 与信額に CONTRACTED を算入、共通の与信枠チェック `financeValidateCustomerCreditLimit`、LEASE アダプタの登録 | 完了（tests=70 / errors=0 / failures=0） |
| L6 | 延滞・督促へのリース用契約アダプタ登録（共通層側の変更） | 未着手 |

L0 のコミット：L0-1 `6d46268`、L0-2 `fe644c8`、L0-3 `ae6fa2e`、L0-4 `2afe7f7`、L0-5 `beea390`、L0-6 `450b654`。

---

## 1. 層構成

| 層 | Java パッケージ | 定義ファイル | 仕様書 |
|----|-----------------|--------------|--------|
| 共通層 | `org.apache.ofbiz.finance.common` | `entitymodel_common.xml` / `services_common.xml` / `FinanceCommon*.xml` / `FinanceCommonTypeData.xml` | 本書 |
| ローン層 | `org.apache.ofbiz.finance.loan` | 既存の `entitymodel*.xml` / `services*.xml` 等 | `SPEC.md` |
| リース層 | `org.apache.ofbiz.finance.lease`（L1〜） | `entitymodel_lease*.xml` / `services_lease*.xml` | `SPEC_LEASE.md` |

依存は「ローン層 → 共通層」「リース層 → 共通層」のみ。共通層の Java はローン層・リース層のクラスを import しない。
契約種別ごとの処理は `FinanceContractAdapter` を介して呼び出し、実装クラスは `finance.properties` に登録して
リフレクションで読み込む（共通層からローン層へのコンパイル時依存を持たない）。

### 1.1 クラス一覧（L0 時点）

| 層 | クラス | 役割 |
|----|--------|------|
| 共通 | `FinanceContractHelper` | 共通契約台帳の作成・状態反映・ロック・与信合算 |
| 共通 | `FinanceContractAdapter` | 契約種別アダプタのインターフェース |
| 共通 | `FinanceContractAdapters` | アダプタ登録簿（`contract.adapter.<type>`）、サービス引数からの契約解決 |
| 共通 | `DelinquencyComputation` | 延滞日数・延滞元本・延滞利息の値オブジェクト |
| 共通 | `FinanceDelinquencyServices` | 延滞判定バッチ・遅延損害金・督促・償却/法的手続き（契約種別に依存しない） |
| 共通 | `FinanceInterestCalculator` | 日割り基準・端算入・通貨別丸め・設定解決（STEP 8〜10 から移動） |
| 共通 | `BusinessDayCalculator` | 営業日調整（STEP 10 から移動） |
| ローン | `FinanceAccountingServices` | 月次利息計上・請求・入金消込（STEP 3）。契約ロックは `FinanceContractHelper` 経由 |
| ローン | `FinancePrepaymentServices` | 繰上返済・条件変更（STEP 7） |
| ローン | `LoanContractAdapter` | ローン用アダプタ（確定返済表と分割請求 Invoice から延滞を算出） |

旧パッケージ `org.apache.ofbiz.finance.accounting` は廃止した。サービス名・リクエスト URI・画面名は変更していない。

---

## 2. 共通契約台帳（FinanceContract）

### 2.1 エンティティ（`entitymodel_common.xml`）

| エンティティ | 主なフィールド | 説明 |
|--------------|----------------|------|
| `FinanceContractType` | contractTypeId (PK), description | `LOAN` / `LEASE`（seed） |
| `FinanceContract` | financeContractId (PK), contractTypeId, customerPartyId, productId, statusId, currencyUomId, principalAmount, contractDate, processingLockVersion, createdDate | 全契約の共通台帳。`customerPartyId` に索引 |

- `principalAmount`：ローンは契約元本、リースは取得価額（与信額の算定に使う）。
- `statusId`（`FINANCE_CTR_STATUS`）：`FINCTR_CONTRACTED` / `FINCTR_ACTIVE` / `FINCTR_COMPLETED` / `FINCTR_CANCELLED`。
  各層の状態の写しで、各層の状態遷移サービスからだけ更新する。

### 2.2 各層との紐付け

- `LoanAgreement.financeContractId`（FK、一意索引 `LOANAGR_FINCTR_UQ`）で 1:1。
- `createLoanAgreement` が契約作成時に `FinanceContract`（`LOAN`、`FINCTR_CONTRACTED`）を作成し、`financeContractId` を返す。
- `setLoanAgreementStatus` がローン契約状態を共通状態へ反映する。

| ローン契約状態 | 共通状態 |
|----------------|----------|
| `LOANAGR_DRAFT` | `FINCTR_CONTRACTED` |
| `LOANAGR_ACTIVE` | `FINCTR_ACTIVE` |
| `LOANAGR_CLOSED` | `FINCTR_COMPLETED` |
| `LOANAGR_CANCELLED` | `FINCTR_CANCELLED` |

### 2.3 契約ロック

- `FinanceContractHelper.lock(financeContractId)`：`processingLockVersion` を条件付き更新で +1 し、
  同一契約の計上・請求・入金・延滞処理を直列化する（競合時は最大 5 回再試行）。
- ローン層の `lockLoanAgreement` はローン契約から `financeContractId` を引いてこのロックを取得し、契約を再読込して返す。
- `LoanAgreement.processingLockVersion` は廃止（列は互換のため残し、更新しない）。

---

## 3. 与信（合算）

- `FinanceContractHelper.sumExposurePrincipal(customerPartyId)`：顧客の `FINCTR_CONTRACTED` と `FINCTR_ACTIVE` の
  `FinanceContract` の `principalAmount` 合計。契約種別を問わない（ローン＋リース）。返済による減額はしない（STEP 5 の方式を踏襲）。
  - **STEP L2 で CONTRACTED を算入**した。リース契約は契約後、検収（L3）まで CONTRACTED のままなので、未開始の契約も与信を使うものとして扱う。
    ローン契約は作成時に即 ACTIVE になるため、ローンの結果は変わらない（DRAFT のまま残ったローン契約がある場合だけ増える）。
  - 旧名 `sumActivePrincipal` は `@Deprecated` の別名として残した。
- 与信枠チェックの本体は共通層の `financeValidateCustomerCreditLimit`（`services_common.xml`、`ValidateCustomerCreditLimit.groovy`、STEP L2）：
  顧客・通貨・新規元本を受け取り、有効な `CreditLine`（顧客単位のマスタとして扱う。エンティティは STEP 5 で定義）があれば
  「既存与信額＋新規元本 ≦ 与信枠」を検証する。
  - ローン：`financeValidateCreditLimit`（`ValidateCreditLimit.groovy`）がローン申込から借手・通貨を引いてこれを呼ぶ（入出力は従来どおり）。
  - リース：審査承認時（`financeValidateLeaseUnderwritingApproval`）と契約作成時（`createLeaseAgreement`）に取得価額で呼ぶ。
- 顧客照会の「与信額」も同じ合計を表示する。

---

## 4. 延滞・督促（共通エンジン）

### 4.1 契約種別アダプタ

`FinanceContractAdapter` は、共通エンジンが必要とする契約種別ごとの処理を提供する。

| メソッド | 内容 |
|----------|------|
| `getContractTypeId` / `getAgreementIdField` / `getAgreementEntityName` / `getDescriptionPrefix` | 種別・キー項目名・エンティティ名・仕訳摘要の接頭辞 |
| `findContractIdByAgreementId` / `findAgreement` | 層の契約 ID と共通契約の相互解決 |
| `isActive` / `getOrganizationPartyId` | 請求対象かどうか、会計組織 |
| `computeDelinquency` | 基準日時点の延滞日数・延滞元本・延滞利息 |
| `findOpenInvoiceIds` | 未入金・未償却の請求書（償却時に `INVOICE_WRITEOFF` にする） |
| `getAgreementKeyFields` | 延滞関連レコードに互換用に保存する層のキー（例 `loanAgreementId`） |

登録（`config/finance.properties`）：

```properties
contract.adapter.LOAN=org.apache.ofbiz.finance.loan.LoanContractAdapter
contract.adapter.LEASE=org.apache.ofbiz.finance.lease.LeaseContractAdapter
```

アダプタ未登録の契約種別は、延滞バッチで処理対象外となる（顧客照会には表示される）。
LEASE は顧客照会・契約画面への遷移のため STEP L2 で登録した。延滞算出は L6 で実装し、それまでは「延滞なし」を返す（`SPEC_LEASE.md` 3.5）。

### 4.2 処理

| サービス | 変更点（L0） |
|----------|--------------|
| `runLoanDelinquencyBatch` | `FINCTR_ACTIVE` の `FinanceContract` を順に処理。契約ロック → アダプタで延滞算出 → 商品の `FinancialProductDelinquency` で区分 → スナップショット・償却・法的手続き |
| `runLateFeeAccrual` / `runLateFeeAccrualInternal` | 契約を `financeContractId` で特定。日割り基準は契約の商品から解決 |
| `createLoanDunningNotice` | 同上 |

- 4 サービスとも `loanAgreementId`（従来）または `financeContractId` で呼び出せる（`loanAgreementId` は任意化）。
- `LoanDelinquency` / `LoanLateFeeAccrual` / `LoanDunningNotice` / `LoanDelinquencyAction` に `financeContractId`（FK・索引）を追加。
  重複防止（冪等性）の判定は `financeContractId` で行い、`loanAgreementId` も互換のため設定する。
- 仕訳の取引先は `FinanceContract.customerPartyId`、通貨は `FinanceContract.currencyUomId`。

---

## 5. 審査

- `LoanUnderwriting` に `applicationTypeId`（FK `FinanceContractType`）と `applicationId` を追加（索引 `LOANUW_APP_IDX`）。
- `createLoanUnderwriting` は `LOAN` と `loanApplicationId` を自動設定する。両項目は create/update の入力から除外。
- 状態規則（`LOANUW_IN_REVIEW` で作成、承認・否決後は変更不可、状態・decision・reviewDate の一貫確定）は従来どおり。
  リース申込の審査は L1 以降で `applicationTypeId=LEASE` として扱う。

### 5.1 申込種別に依存しない審査（STEP L1）

| 項目 | 内容 |
|------|------|
| `createFinanceUnderwriting`（`services_common.xml`） | `applicationTypeId` + `applicationId` を必須にした共通の審査作成。実装は `createLoanUnderwriting` と同じ `SaveLoanUnderwriting.groovy`（`LOAN` の場合だけ `loanApplicationId` も設定） |
| 承認時の検証 | `setLoanUnderwritingStatus` で `LOANUW_APPROVED` にするとき、`LOAN` は従来の担保 LTV・与信枠チェック、それ以外は `finance.properties` の `underwriting.approval.validator.<type>` に登録したサービスを `applicationId` / `principalAmount` で呼ぶ。未登録の種別は承認できない |
| 登録 | `underwriting.approval.validator.LEASE=financeValidateLeaseUnderwritingApproval`（リース層、`SPEC_LEASE.md` 2.3） |

共通層の Groovy はリース層のサービス名を直接持たず、設定経由で呼ぶ。

---

## 5A. 金融商品画面（STEP L1）

ローン・リース共通の商品画面を追加した（ローン用の商品画面は従来なかった）。

| 項目 | 内容 |
|------|------|
| ビュー | `FinancialProductAndType`（`entitymodel_common.xml`）：`FinancialProduct` ＋ 種別の `parentTypeId`（LOAN / LEASE）・説明 ＋ core `Product.productName` |
| サービス | `createFinanceProduct`：core `Product`（`SERVICE`）がなければ `productName` で作成し、`FinancialProduct` を作成。`productId` 省略時は採番。更新は既存の `updateFinancialProduct` |
| 画面 | `FindFinancialProduct`（業務・商品種別・ID・名称で検索）、`EditFinancialProduct`（登録・編集）。タブ `FinancialProductTabBar` はリース商品にだけ「リース条件」を表示 |

率の項目は小数（0.15 = 15%）で入力する。

---

## 6. 顧客照会

| 項目 | 内容 |
|------|------|
| サービス | `getFinanceCustomerSummary`（`services_common.xml`、VIEW 権限）：顧客の全 `FinanceContract`（層の契約番号をアダプタで解決）、有効契約の与信額合計、契約ごとの最新延滞スナップショット、督促通知 |
| 画面 | `FinanceCustomerSummary`（メニュー「顧客照会」）：顧客検索（LookupPartyName）、顧客名・与信額、契約一覧、延滞、督促 |
| 遷移 | `ViewFinanceContract?financeContractId=...`：契約種別に応じて各層の管理画面へリダイレクト（`LOAN` → `ManageLoanAgreement`） |

---

## 7. 設定・データ

| ファイル | 種別 | 内容 |
|----------|------|------|
| `data/FinanceCommonTypeData.xml` | seed | `FinanceContractType`（LOAN/LEASE）、`FINANCE_CTR_STATUS` |
| `data/FinanceStatusDemoData.xml` | demo | デモのローン契約 8 件に対応する `FinanceContract`（`FDCTR_*`）、審査の `applicationTypeId` / `applicationId` |
| `config/finance.properties` | 設定 | `contract.adapter.LOAN` |

L0-1 で、未登録だった `FinanceStep8TypeData.xml` / `FinanceStep9TypeData.xml` を `ofbiz-component.xml` の seed に登録した。

---

## 8. 検証

| 検証項目 | 方法 | 結果 |
|----------|------|------|
| Java / Groovy | `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| finance テスト | `gradlew "ofbiz --test component=finance"` | tests=50 / errors=0 / failures=0 |
| demo 投入 | `gradlew "ofbiz --load-data readers=demo --load-data component=finance"` | BUILD SUCCESSFUL |
| 層の依存 | `common` パッケージに `finance.loan` / `finance.lease` への参照がないこと | 該当なし |

L0 で追加したテスト：`FinanceContract` の作成と状態反映、与信合算（リース契約の加算・取消契約の除外）、
延滞レコードの `financeContractId` と契約 ID 指定での呼出し、審査の申込参照、顧客照会。

---

## 9. GL 計上部品（STEP L3）

`org.apache.ofbiz.finance.common.FinanceGlHelper`：

| メソッド | 内容 |
|----------|------|
| `findGlAccountId(org, glAccountTypeId)` | 組織の `GlAccountTypeDefault`、なければ組織に割り当てた（`GlAccountOrganization`）同じタイプの最初の科目。ローン層の同名の非公開メソッドと同じ規則 |
| `postTransaction(...)` | 複数明細の取引を、core の `createAcctgTransAndEntries` で作成して転記する（まだ転記されていない場合だけ `postAcctgTrans` を呼ぶ）。科目の割当がない、換算できない、貸借が一致しない場合はエラー。金額 0 の明細は作らない |
| `postUnposted(field, value)` | `invoiceId` / `paymentId` を持つ未転記の取引を転記する |

- 金額は契約通貨で受け取る。組織の基準通貨（`PartyAcctgPreference`）と違うときは明細ごとに `convertUom`（取引日基準）で換算して小数 2 桁に丸め、端数差は最大の貸方明細で調整する。元の金額・通貨は `origAmount` / `origCurrencyUomId` に残す。
- 取引種別は `OTHER_INTERNAL`、会計種別は `ACTUAL`（ローン層の月次計上と同じ）。
- ローン層の仕訳処理（`FinanceAccountingServices`）は L3 では変更していない。共通化は必要になった時点で行う。

---

## 運用メモ

- **DB 項目追加**：L0 で `FinanceContract` / `FinanceContractType` を新設し、`LoanAgreement`・`LoanUnderwriting`・
  延滞関連 4 エンティティに列を追加した。OFBiz 起動時の自動スキーマ更新（列追加）で反映される。
  L0 以前に作成された既存ローン契約は `financeContractId` が空のため、計上・請求・入金・延滞処理でエラーになる。
  既存環境では `FinanceContract` を作成して紐付けるか、`--load-data readers=seed,seed-initial,demo` で再投入すること。
- **seed の投入**：テスト用 delegator は既定 DB を共有するため、新しい seed ファイルを登録した後は
  `gradlew "ofbiz --load-data readers=seed --load-data component=finance"` を実行してからテストする
  （未投入だと `FinanceContractType` の FK 違反で契約作成が失敗する）。
- **テスト中断時の残存データ**：テスト実行が途中で中断されると、トランザクション外で作成済みのテストデータが残り、
  次回の `testCreateAgreementWithConfirmedSchedule` が「審査未承認でも契約できてしまう」として失敗することがある
  （`TEST_LOANAPP_AGR` に承認済みの `LoanUnderwriting` が残るため）。最後まで実行されたテストではこの行は残らない。
  発生時は該当の `LoanUnderwriting` を削除して再実行する。
- **テストデータの残存と分離**（STEP L2）：テストは既定 DB を共有し、テストで作った申込・契約・与信枠などは残ることがある。
  残ると他のテストの前提を変えるデータ（顧客単位の `CreditLine`、与信額に効く有効契約）は、テストごとに新しい顧客を作って分離するか、
  終了時に取消して与信額に残さない（`LeaseQuoteAgreementTests` の与信テストは毎回新しい借手を作成）。
  L2 の開発中に、旧版のテストが残した `TEST_LESSEE1` の `CreditLine`（説明 `Lease credit test`）2 件を削除した。
  また、ローン申込とリース申込の ID は別の採番なので同じ値になり得る。審査を申込で引くときは `applicationTypeId` も条件にする。
