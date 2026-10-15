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
| L10a | 契約×取引先×役割×持分率（`FinanceContractParty`）と契約の親子関係（10 章） | 完了（tests=138 / errors=0 / failures=0） |
| L15a | 誤操作の取消（取消記録・逆仕訳・管理者権限。11 章。ローン・リースの各操作は `SPEC.md` 27 章・`SPEC_LEASE.md` 18 章） | 完了（tests=192、取消の 6 件はすべて成功。全体の結果は `SPEC_LEASE.md` 18.6 を参照） |

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
| 共通 | `DelinquencyComputation` | 延滞日数・延滞元本・延滞利息の値オブジェクト（STEP L6 で延滞消費税と遅延損害金の計算基礎を追加。償却科目はアダプタの `getWriteOffCreditAccountType`。詳細は `SPEC_LEASE.md` 7.2） |
| 共通 | `FinanceWorkEffortHelper` | STEP L7：契約のタスク（core WorkEffort、`FinanceContractWorkEffort` で紐付け）の作成（冪等）・完了・一覧、サービス `createFinanceContractTask`。詳細は `SPEC_LEASE.md` 8.1 |
| 共通 | `FinanceContractPartyHelper` | STEP L10a：契約の取引先（役割・持分・期間）と親子関係。照会部品、主取引先の自動登録、サービス 4 本（10 章） |
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

## 10. 契約の取引先と親子関係（STEP L10a）

要件「後続 STEP の設計上の考慮事項 1.（土台）」（`REQUIREMENTS_LEASE.md`）。協調リース・転貸・フリート・ベンダープログラム（L16〜L20）で
作り直しが起きないよう、契約×取引先×役割×持分率と契約の親子関係を共通層に置く。**追加のみ**で、既存処理の挙動は変えない。

### 10.1 データモデル（`entitymodel_common.xml`）

| エンティティ / 列 | 主キー | 内容 |
|-------------------|--------|------|
| `FinanceContractParty` | financeContractId + partyId + roleTypeId + fromDate | 契約の取引先：役割（core `RoleType`、`FIN_CTR_ROLE` 配下）、持分率 `shareRate`（0 超 1 以下、空＝持分なし）、期間（from/thru、thru 空＝有効）、`isPrimary`（各層の契約から登録した借手・貸手等）、備考 |
| `FinanceContract.parentFinanceContractId` | — | 親契約（基本契約：フリート、ベンダープログラム、協調リース全体）。空＝最上位。循環は禁止 |

core の `Agreement` / `AgreementRole` は使わない（`FinanceContract` は core の Agreement ではなく、持分率・期間を持たせる必要があるため）。役割は core の `RoleType` を使う。

### 10.2 役割（seed `FinanceCommonTypeData.xml`）

`FIN_CTR_ROLE`（親）配下：`FIN_BORROWER` 借主 / `FIN_LENDER` 貸主 / `FIN_LESSEE` 借手 / `FIN_LESSOR` 貸手 / `FIN_GUARANTOR` 保証人 /
`FIN_PARTICIPANT` 協調参加者 / `FIN_ARRANGER` 幹事 / `FIN_HEAD_LESSOR` 元リースの貸手 / `FIN_VENDOR` 販売店 / `FIN_RECOURSE` リコース先。

### 10.3 主取引先の登録

- `FinanceContractHelper.createContract` に貸手組織つきの版を追加した（従来の版は組織なしで残す）。契約作成時に、顧客（`customerPartyId`）と貸手組織を **持分 100%・`isPrimary=Y`・開始日＝契約日** で登録する。
- 役割は契約種別のアダプタが返す：`FinanceContractAdapter.getCustomerRoleTypeId` / `getOrganizationRoleTypeId`（既定＝ローン：借主・貸主、`LeaseContractAdapter`：借手・貸手）。共通層はローン層・リース層を参照しない。
- 呼び出し元：ローン `CreateLoanAgreement.groovy`（組織＝`organizationPartyId`、既定 Company）、リース `createLeaseAgreement`（貸手組織）。
- **既存契約**：`backfillFinanceContractParties` が、主取引先のない契約に顧客と（アダプタで解決できれば）貸手組織を登録する。役割ごとに既存行があれば作らない（冪等）。

### 10.4 サービス（`services_common.xml`、`FinanceContractPartyHelper`）

| サービス | 内容 |
|----------|------|
| `addFinanceContractParty` | 役割は `FIN_CTR_ROLE` 配下のみ、持分は 0 超 1 以下、同じ取引先・役割の重複（開始日時点で有効）を拒否、同じ役割の有効な持分合計が 100% を超えるなら拒否 |
| `expireFinanceContractParty` | 終了日（既定＝現在）を設定。開始日より前・終了済みは拒否 |
| `setFinanceContractParent` | 親契約の設定・解除（空）。存在しない親、自分自身、循環（親の祖先に自分を含む）を拒否 |
| `backfillFinanceContractParties` | 10.3 の既存契約の登録 |

照会部品：`findParties(契約, 役割, 時点)`（期間で有効な行）、`isContractRole`、`ancestors`（親→祖父…）、`registerPrimaryParties`。

### 10.5 画面

`FinanceContractParties`（ローン・リース契約画面のボタン「契約の取引先」から）：契約ヘッダ・取引先一覧（役割・持分・期間・有効・役割の終了）、取引先の追加、親契約の設定、子契約の一覧。

### 10.6 既存処理への影響

なし。請求・入金・与信（`sumExposurePrincipal` は `customerPartyId`）・延滞・督促・固定資産税（`FixedAsset.partyId`。L10b 以降は公租公課 `LeasePublicCharge*`、物件の持分は `LeaseAgreementAsset.ownershipShare`）は従来どおり各層の取引先列を読む。
土台を読むように切り替えるのは、複数の貸手・持分が必要になる L16 以降とする。

### 10.7 テスト（`FinanceContractPartyTests`）

| テスト | 内容 |
|--------|------|
| `testPrimaryPartiesOnCreation` | リース契約（直接作成・リースのサービス経由）に借手・貸手 100%、組織なしの旧版は借主のみ、照会部品 |
| `testAddExpireAndShares` | 参加者 60%＋40%、100% 超・重複・対象外の役割・持分の範囲外・存在しない取引先の拒否、持分なしの保証人、役割の終了（開始日前・二重終了の拒否）、終了後の持分の再割当、時点指定の照会 |
| `testParentAndCycle` | 3 階層の親子と祖先、循環・自分自身・存在しない親の拒否、解除 |
| `testBackfill` | 取引先のない旧契約への登録（層の契約がないため借手のみ）、再実行で 0 件 |

### 10.8 検証

| 検証項目 | 結果 |
|----------|------|
| `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| seed 投入 | `FINANCE_CONTRACT_PARTY` 新設、`FINANCE_CONTRACT.PARENT_FINANCE_CONTRACT_ID` 追加、役割 11 件 |
| `gradlew "ofbiz --test component=finance"` | tests=138 / errors=0 / failures=0（L10a の 4 件を含む） |
| 画面 | 未確認 |

### 10.9 運用メモ

- 既存環境では seed 投入後に `backfillFinanceContractParties` を 1 回実行する（テスト `testBackfill` が開発 DB 全体に対して実行するため、開発 DB の既存契約は登録済み）。
- 手動で追加した取引先（保証人・参加者等）は、現時点では業務処理に使われない（記録のみ）。

---

## 11. 誤操作の取消（STEP L15a）

誤って実行した操作を取り消す。対象はリースの検収・中途解約・条件変更と、ローンの契約（有効化）・繰上返済・条件変更（各層の内容は `SPEC_LEASE.md` 18 章・`SPEC.md` 27 章）。

### 11.1 方針（2026-10-15 決定）

| 項目 | 決定 |
|------|------|
| 方式 | **操作ごとに専用の取消サービス**（案A）。元の記録は消さず、逆仕訳と取消記録を足す。行を消す必要があるもの（検収記録・解約記録）は取消記録に写し（スナップショット）を残す |
| 取り消せる条件 | **その後の処理がない場合だけ**（請求・入金・戻入・償却・計上・延滞・後続の変更 等）。後続の処理がある場合はエラー（`FinanceReversalBlocked`、何があるかを示す） |
| 権限 | **FINANCE_ADMIN だけ**（`financeAdminPermission`。他の finance 権限では不可） |
| 理由 | **必須**（空白は不可） |
| 対象 | リース：検収・中途解約・条件変更。ローン：有効化・繰上返済・条件変更 |

### 11.2 共通部品

| 部品 | 内容 |
|------|------|
| `FinanceReversal`（entity） | 契約、取消の種類（`FINANCE_REV_TYPE`：`FINREV_*`）、取り消した記録の ID、操作日、理由、取消日、取消者、逆仕訳の ID（カンマ区切り）、消した行の写し |
| `FinanceReversalHelper` | 取消記録の作成、写しの作成、理由の検査、「ある時点より後に作られた行」の件数 |
| `FinanceGlHelper.reverseTransaction` | 仕訳の借方・貸方を入れ替えた逆仕訳を作って転記する（元の仕訳は残す） |
| `FinanceWorkEffortHelper.cancelAndUnlinkTask` | タスクを取り消し、紐付けを外す（同じきっかけで再びタスクを作れるようにする） |
| `financeAdminPermission`（service / minilang） | FINANCE_ADMIN を持つ利用者だけを許可する |

### 11.3 状態遷移

取消用の遷移を `StatusValidChange` に追加したが、汎用の状態サービス（`setLeaseAgreementStatus` / `setLoanAgreementStatus` / `setLoanApplicationStatus`）では使えないようにしている（`FinanceReversalRequiresService`）。

| 層 | 遷移 |
|----|------|
| リース | `LEASEAGR_ACTIVE` → `LEASEAGR_CONTRACTED`（検収の取消）、`LEASEAGR_TERMINATED` → `LEASEAGR_ACTIVE`（解約の取消）、再リースの戻し 2 本 |
| ローン | `LOANAGR_ACTIVE` → `LOANAGR_CANCELLED`（有効化の取消）、`LOANAGR_CLOSED` → `LOANAGR_ACTIVE`（全額繰上返済の取消）、`LOANAPP_CONTRACTED` → `LOANAPP_APPROVED` |

### 11.4 要確認・制約

- **後続の処理がある場合**：まとめて戻す（請求の取消→入金の取消→…）ことはしない。先に後続の処理を core の機能（請求書の取消・入金の取消）で戻してから取消すること。ただし finance の計上記録（戻入・償却・計上）を戻すサービスはないため、計上後の取消はできない。
- **取消の取消**：取り消した操作をもう一度行う（検収し直す 等）ことで対応する。取消そのものを戻すサービスはない。
- **仕訳の日付**：逆仕訳は取消した日付で計上する（元の操作日ではない）。締めた会計期間の操作を取り消すと、当期に影響する。
- **対象外の操作**：物件購入・支払、返還・処分・譲渡、再リースの開始、月次の計上、公租公課の申告・納付は対象外（必要なら同じ方式で追加する）。

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
