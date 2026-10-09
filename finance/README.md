# Finance プラグイン 仕様（STEP 1〜STEP 5 完了分）

Apache OFBiz 上に構築する独立したファイナンスモジュール。
*The Data Model Resource Book* の **Financial Services** をベースに、ローン業務を提供する。
全体計画はリポジトリ直下の `REQUIREMENTS.md` を参照。

このドキュメントは **STEP 1〜STEP 5**（REQUIREMENTS.md の全ステップ）の仕様をまとめたもの。

- STEP 1: ローン申込の CRUD、ステータス遷移、検索一覧 / 登録編集画面
- STEP 2: 見積（返済計画シミュレーション）、審査、契約（ローン口座＝FinAccount 開設）
- STEP 3: 月次利息計上、請求（Invoice 生成）、入金消込（Payment 適用）＋単体テスト
- STEP 4: 担保付ローン（Collateral の登録・評価・紐付け、LTV 上限の強制）＋単体テスト
- STEP 5: 事業者向けローン（与信枠の限度額チェック、保証人の記録）＋単体テスト

STEP 2 はセクション 9〜12、STEP 3 はセクション 13〜17、STEP 4 はセクション 20、STEP 5 はセクション 21 にまとめている。
実装言語の方針：計算・会計・バッチ＝**Java**、CRUD / ステータス遷移 / 画面データ準備＝**entity-auto / Groovy**。

---

## 1. 設計方針（確定事項）

| 項目 | 決定内容 |
|------|----------|
| 配置 | `plugins/finance`（独立プラグイン。OFBiz 本体 `applications/` は非改変） |
| Product の拡張方式 | **方法B**：既存 `Product` を変更せず、`FinancialProduct` が `productId` で 1:1 参照する |
| 対象ローン | 個人向け無担保から開始。種別構造で担保付・事業者向けへ拡張可能 |
| 既存モジュール再利用 | `party`（申込者）, `product`（ローン商品）, `accounting`（会計・請求）, `workeffort`（審査） |

### プラグインの自動ロード

`common.gradle` の `activeComponents()` が `plugins/` 配下で `ofbiz-component.xml`（`enabled="true"`）を持つディレクトリを自動検出する。
`plugins/component-load.xml` は存在しないため、`finance` は追加設定なしで active component として読み込まれる。

---

## 2. ディレクトリ構成

```
plugins/finance/
├── ofbiz-component.xml          # コンポーネント定義（entitymodel + seed + service + webapp + test 登録）
├── build.gradle                 # 最小構成（src/main/java はルート build.gradle が自動コンパイル）
├── entitydef/
│   ├── entitymodel.xml          # STEP 1 エンティティ（方法B）
│   ├── entitymodel_step2.xml    # STEP 2 エンティティ（見積 / 審査 / 契約）
│   ├── entitymodel_step3.xml    # STEP 3 エンティティ（請求対応 / 利息計上 / 入金対応）
│   ├── entitymodel_step4.xml    # STEP 4 エンティティ（担保 / 評価 / 紐付け）
│   └── entitymodel_step5.xml    # STEP 5 エンティティ（与信枠 / 保証人）
├── servicedef/
│   ├── services.xml             # STEP 1: CRUD + ステータス遷移 + 権限サービス
│   ├── services_step2.xml       # STEP 2: 見積 / 審査 / 契約サービス
│   ├── services_step3.xml       # STEP 3: 経理 / 請求 / 入金サービス（Java）
│   ├── services_step4.xml       # STEP 4: 担保 CRUD / 評価 / 紐付け / LTV サービス
│   └── services_step5.xml       # STEP 5: 与信枠 / 保証人 / 限度額検証サービス
├── src/main/java/org/apache/ofbiz/finance/accounting/
│   └── FinanceAccountingServices.java   # STEP 3 業務ロジック（Java）
├── src/main/groovy/org/apache/ofbiz/finance/test/
│   ├── LoanLifecycleTests.groovy        # STEP 3 単体テスト（OFBizTestCase）
│   ├── LoanCollateralTests.groovy       # STEP 4 単体テスト（OFBizTestCase）
│   └── LoanBusinessLoanTests.groovy     # STEP 5 単体テスト（OFBizTestCase）
├── groovyScripts/
│   ├── SetLoanApplicationStatus.groovy  # STEP 1 ステータス遷移
│   ├── CreateLoanQuote.groovy           # STEP 2 見積・返済計画算出
│   ├── CreateLoanAgreement.groovy       # STEP 2 契約確定 + FinAccount 開設
│   └── SetFinanceEntityStatus.groovy    # STEP 2 共通ステータス遷移
├── minilang/
│   └── FinancePermissionServices.xml    # 権限チェック
├── config/
│   └── FinanceUiLabels.xml      # UI ラベル（en / ja、STEP 1〜3）
├── widget/
│   ├── CommonScreens.xml        # main-decorator
│   ├── FinanceMenus.xml         # アプリバー / タブバー
│   ├── FinanceForms.xml         # STEP 1 検索 / 一覧 / 編集 / ステータス変更
│   ├── FinanceScreens.xml       # STEP 1 検索一覧画面 / 編集画面
│   ├── FinanceStep2Forms.xml    # STEP 2 見積 / 返済スケジュール / 審査フォーム
│   ├── FinanceStep2Screens.xml  # STEP 2 管理画面 / 見積ビュー画面
│   ├── FinanceStep3Forms.xml    # STEP 3 利息計上 / 請求 / 入金フォーム・一覧
│   ├── FinanceStep3Screens.xml  # STEP 3 契約一覧 / 契約管理画面
│   ├── FinanceStep4Forms.xml    # STEP 4 担保 / 申告 / 評価 / 紐付けフォーム・一覧
│   ├── FinanceStep4Screens.xml  # STEP 4 担保一覧 / 担保編集画面
│   ├── FinanceStep5Forms.xml    # STEP 5 与信枠 / 保証人フォーム・一覧
│   └── FinanceStep5Screens.xml  # STEP 5 与信枠一覧 / 与信枠編集画面
├── webapp/finance/
│   ├── index.jsp                # /control/main へリダイレクト
│   └── WEB-INF/
│       ├── controller.xml       # リクエスト / ビューマッピング（STEP 1〜3）
│       └── web.xml              # webapp 設定（mount-point /finance）
├── testdef/
│   ├── financetests.xml         # テストスイート定義
│   └── data/
│       └── FinanceTestData.xml  # テスト用データ
└── data/
    ├── FinanceTypeData.xml                    # STEP 1 型・ステータス seed
    ├── FinanceStep2TypeData.xml               # STEP 2 型・ステータス・列挙 seed
    ├── FinanceStep3TypeData.xml               # STEP 3 InvoiceItemType seed
    ├── FinanceStep4TypeData.xml               # STEP 4 担保種別・担保STATUS・評価方法 seed
    ├── FinanceStep5TypeData.xml               # STEP 5 与信枠種別・保証種別 seed
    ├── FinanceSecurityPermissionSeedData.xml  # 権限 seed データ
    ├── FinanceExchangeRateDemoData.xml         # demo専用 USD/JPY 固定換算レート
    ├── FinanceStep4DemoData.xml                # demo専用 担保付商品・担保・申告
    ├── FinanceStep5DemoData.xml                # demo専用 事業者商品・与信枠・保証人
    └── FinanceStatusDemoData.xml               # 全業務STATUSのJPYデモスナップショット
```

この一覧は `plugins/finance/` 配下の構成である。STEP 3 の消込解除整合性対応では、次の **OFBiz coreファイルも1件変更している（プラグイン外）**。

```text
applications/accounting/src/main/groovy/org/apache/ofbiz/accounting/payment/PaymentServices.groovy
```

core変更の内容・理由・影響範囲は「14. STEP 3サービス」の「`plugins/finance` 外のcore修正」を参照。

---

## 3. データモデル（`entitydef/entitymodel.xml`）

### FinancialProductType — ローン種別
金融商品（ローン）の種別を階層で管理する区分エンティティ。

| フィールド | 型 | 説明 |
|-----------|----|----|
| financialProductTypeId (PK) | id | 種別ID |
| parentTypeId | id | 親種別（階層化用） |
| hasTable | indicator | |
| description | description | |

seed 値：`LOAN`（ルート） / `PERSONAL_UNSECURED` / `SECURED` / `BUSINESS`

### FinancialProduct — ローン商品（Product の 1:1 拡張 / 方法B）
既存 `Product` を変更せず、`productId` を主キー兼 FK として 1:1 で関連づける。

| フィールド | 型 | 説明 |
|-----------|----|----|
| productId (PK) | id | 既存 `Product.productId` への FK（1:1） |
| financialProductTypeId | id | → FinancialProductType |
| currencyUomId | id | → Uom（通貨） |
| minPrincipalAmount | currency-amount | 最小元本 |
| maxPrincipalAmount | currency-amount | 最大元本 |
| minTermMonths | numeric | 最小返済期間（月） |
| maxTermMonths | numeric | 最大返済期間（月） |
| defaultAnnualInterestRate | fixed-point | 年利（例 0.150000 = 15%） |
| requiresCollateral | indicator | 担保要否（担保付ローン用） |

リレーション：`Product`（FINPROD_PROD） / `FinancialProductType`（FINPROD_TYP） / `Uom`（FINPROD_UOM）

### LoanApplication — ローン申込
取引先（Party）から受け付けるローン申込。

| フィールド | 型 | 説明 |
|-----------|----|----|
| loanApplicationId (PK) | id | 申込ID（採番） |
| productId | id | 申込対象のローン商品 → FinancialProduct |
| applicantPartyId | id | 申込者 → Party（個人／組織） |
| statusId | id | → StatusItem（申込ステータス） |
| currencyUomId | id | → Uom |
| requestedPrincipalAmount | currency-amount | 希望元本 |
| requestedTermMonths | numeric | 希望返済期間（月） |
| applicationDate | date-time | 申込日時 |
| purpose | description | 資金使途 |
| comments | comment | 備考 |

リレーション：`FinancialProduct`（LOANAPP_PROD） / `Party`（LOANAPP_PARTY） / `StatusItem`（LOANAPP_STTS） / `Uom`（LOANAPP_UOM）

### LoanApplicationStatus — 申込ステータス履歴
申込のステータス遷移を監査用に記録する。

| フィールド | 型 | 説明 |
|-----------|----|----|
| loanApplicationId (PK) | id | → LoanApplication |
| statusId (PK) | id | → StatusItem |
| statusDate (PK) | date-time | 遷移日時 |
| changeByUserLoginId | id-vlong | 変更者 |

---

## 4. seed データ（`data/FinanceTypeData.xml`）

### 申込ステータス（StatusType = `LOAN_APP_STATUS`）

| statusId | code | 説明 |
|----------|------|------|
| LOANAPP_RECEIVED | RECEIVED | 受付 |
| LOANAPP_SUBMITTED | SUBMITTED | 審査提出 |
| LOANAPP_CANCELLED | CANCELLED | 取消 |
| LOANAPP_REJECTED | REJECTED | 否決 |

### 許可されるステータス遷移（StatusValidChange）

| from | to | 遷移名 |
|------|----|-------|
| LOANAPP_RECEIVED | LOANAPP_SUBMITTED | Submit for review |
| LOANAPP_RECEIVED | LOANAPP_CANCELLED | Cancel |
| LOANAPP_SUBMITTED | LOANAPP_REJECTED | Reject |
| LOANAPP_SUBMITTED | LOANAPP_CANCELLED | Cancel |

---

## 5. サービス（`servicedef/services.xml`）

| サービス名 | エンジン | 説明 |
|-----------|---------|------|
| financeGenericPermission | simple (minilang) | 権限チェック（`FINANCE_*` パーミッション） |
| createFinancialProduct / updateFinancialProduct / deleteFinancialProduct | entity-auto | ローン商品 CRUD |
| createLoanApplication / updateLoanApplication / deleteLoanApplication | groovy / entity-auto | 申込作成は RECEIVED 固定、内容更新は RECEIVED 中のみ、削除は entity-auto |
| setLoanApplicationStatus | groovy | ステータス遷移。`StatusValidChange` で遷移の妥当性を検証し、`LoanApplicationStatus` に履歴を記録 |

- 権限ロジックは `minilang/FinancePermissionServices.xml`。
- ステータス遷移ロジックは `groovyScripts/SetLoanApplicationStatus.groovy`。
- 権限は `data/FinanceSecurityPermissionSeedData.xml` で定義（`FINANCE_VIEW/CREATE/UPDATE/DELETE/ADMIN`、`SUPER` グループに `FINANCE_ADMIN` を付与）。

## 6. 画面（`widget/` + `webapp/finance/`）

| 画面 / URI | 内容 |
|-----------|------|
| `FindLoanApplication` | 申込の検索フォーム + 結果一覧（新規作成リンク付き） |
| `EditLoanApplication` | 申込の新規登録 / 編集フォーム + ステータス変更フォーム（詳細兼編集） |

- デコレータは `widget/CommonScreens.xml` の `main-decorator`。
- メニューは `widget/FinanceMenus.xml`（アプリバー `FinanceAppBar`、タブバー `LoanApplicationTabBar`）。
- フォーム定義は `widget/FinanceForms.xml`、画面定義は `widget/FinanceScreens.xml`。
- webapp は `/finance` にマウント（`webapp/finance/WEB-INF/controller.xml`, `web.xml`）。
- UI ラベルは英語・日本語を `config/FinanceUiLabels.xml` に定義。

---

## 7. 検証状況

| 検証項目 | 方法 | 結果 |
|----------|------|------|
| エンティティ定義の構文妥当性 | OFBiz 公式 `framework/entity/dtd/entitymodel.xsd` でスキーマ検証 | errors=0 / warnings=0 |
| サービス / component / minilang / controller / UiLabels | 各対応 XSD でスキーマ検証 | errors=0（5/5） |
| widget（Screens / Menus / Forms） | widget XSD（HTTP include をローカル XSD へ解決して）スキーマ検証 | errors=0 / warnings=0（4/4） |
| seed データ・web.xml | XML 整形式チェック | OK |
| ステータス遷移 groovy スクリプト | OFBiz ビルド済みクラスをクラスパスに通して Groovy コンパイル | COMPILE_EXIT=0（構文・OFBiz クラス参照とも正常） |
| 参照先エンティティの実在 | `Product` / `Party` / `Uom` / `StatusItem` / `StatusType` / `StatusValidChange` の定義を確認 | 実在を確認 |

### 検証中に修正した問題

- `services.xml` で `financeGenericPermission` を `group` エンジンで誤記していたのを、XSD 検証で検出し、単一の `simple`（minilang）サービスに修正。

### 未実施（制約）

- `gradlew loadDefault` によるエンティティエンジンへの実ロード検証は未実施。
  Derby への全データ投入を伴い処理が重いため省略した（gradle / ビルド環境自体は利用可能）。
  実ロードまで確認する場合はバックグラウンド実行を推奨。

---

## 8. STEP 1 の補強候補

- サービステスト（`testdef/`）、demo データ、申込一覧からの直接ステータス操作

---

## 9. STEP 2 データモデル（`entitydef/entitymodel_step2.xml`）

### LoanQuote — 見積（返済計画）
ローン申込に対する金利・返済計画の提示。

| フィールド | 型 | 説明 |
|-----------|----|----|
| loanQuoteId (PK) | id | 見積ID |
| loanApplicationId | id | → LoanApplication |
| statusId | id | → StatusItem（LOAN_QUOTE_STATUS） |
| currencyUomId | id | → Uom |
| principalAmount | currency-amount | 見積元本 |
| annualInterestRate | fixed-point | 年利 |
| termMonths | numeric | 返済期間（月） |
| repaymentMethodEnumId | id | → Enumeration（元利均等 / 元金均等） |
| monthlyPaymentAmount | currency-amount | 毎月返済額（元利均等時） |
| totalPaymentAmount | currency-amount | 総返済額 |
| totalInterestAmount | currency-amount | 利息合計 |
| firstPaymentDate / createdDate | date-time | 初回返済日 / 作成日 |

### LoanRepaymentSchedule — 返済スケジュール明細
見積に紐づく各回の返済内訳。

| フィールド | 型 | 説明 |
|-----------|----|----|
| loanQuoteId (PK) | id | → LoanQuote |
| installmentNumber (PK) | numeric | 回次（1 始まり） |
| dueDate | date-time | 返済日 |
| paymentAmount / principalAmount / interestAmount | currency-amount | 返済額 / 元金 / 利息 |
| remainingBalance | currency-amount | 返済後残高 |

### LoanAgreementRepaymentSchedule — 契約確定返済スケジュール
受理済み見積の返済スケジュールを契約作成時に複製する不変のスナップショット。STEP 3 の請求は見積案ではなく、この確定スケジュールを参照する。
物理テーブル名は30文字制限を考慮して `LOAN_AGR_REPAY_SCHED` とする。

| フィールド | 型 | 説明 |
|-----------|----|----|
| loanAgreementId (PK) | id | → LoanAgreement |
| installmentNumber (PK) | numeric | 回次（1 始まり） |
| loanQuoteId | id | 複製元の受理済み LoanQuote |
| dueDate | date-time | 確定返済日 |
| paymentAmount / principalAmount / interestAmount | currency-amount | 確定返済額 / 元金 / 利息 |
| remainingBalance | currency-amount | 返済後残高 |

### LoanUnderwriting — 審査
申込の与信判定。

| フィールド | 型 | 説明 |
|-----------|----|----|
| loanUnderwritingId (PK) | id | 審査ID |
| loanApplicationId | id | → LoanApplication |
| statusId | id | → StatusItem（LOAN_UW_STATUS） |
| creditScore | numeric | 信用スコア |
| decision | short-varchar | APPROVED / REJECTED / PENDING |
| approvedPrincipalAmount / approvedAnnualInterestRate | currency-amount / fixed-point | 承認元本 / 承認金利 |
| reviewerPartyId | id | → Party（審査者） |
| reviewDate / comments | date-time / comment | 審査日 / 備考 |

### LoanAgreement — 契約
確定した契約。**既存の `FinAccount` をローン口座として参照**し、既存 `Agreement` へ任意リンク。

| フィールド | 型 | 説明 |
|-----------|----|----|
| loanAgreementId (PK) | id | 契約ID |
| loanApplicationId / loanQuoteId | id | → LoanApplication / 受理した LoanQuote |
| agreementId | id | → 既存 Agreement（任意） |
| finAccountId | id | → 既存 FinAccount（開設したローン口座） |
| statusId | id | → StatusItem（LOAN_AGR_STATUS） |
| borrowerPartyId / currencyUomId | id | → Party（借主）/ Uom |
| principalAmount / annualInterestRate / termMonths | - | 契約条件 |
| agreementDate / firstPaymentDate | date-time | 契約日 / 初回返済日 |

### STEP 2 の seed（`data/FinanceStep2TypeData.xml`）

- 返済方法 Enumeration：`LOAN_RPM_EQUAL_PAY`（元利均等）/ `LOAN_RPM_EQUAL_PRIN`（元金均等）
- `FinAccountType` = `LOAN_ACCOUNT`（ローン口座タイプ）
- ステータス：`LOAN_QUOTE_STATUS` / `LOAN_UW_STATUS` / `LOAN_AGR_STATUS`（各 StatusItem + StatusValidChange）
- 申込ステータス追加：`LOANAPP_APPROVED`（承認）/ `LOANAPP_CONTRACTED`（契約済）と対応する遷移

---

## 10. STEP 2 サービス（`servicedef/services_step2.xml`）

| サービス名 | エンジン | 説明 |
|-----------|---------|------|
| createLoanQuote | groovy | 申込から見積を作成し、返済スケジュールを生成（元利均等 / 元金均等） |
| updateLoanQuote | groovy | 見積と返済表の整合性維持のため更新を拒否（条件変更は新規見積） |
| setLoanQuoteStatus | groovy | 見積のステータス遷移 |
| createLoanUnderwriting / updateLoanUnderwriting | groovy | 審査を IN_REVIEW / PENDING で作成し、審査中のみ入力項目を更新 |
| setLoanUnderwritingStatus | groovy | 審査中 → 承認 / 否決。判定値と審査日も同期 |
| createLoanAgreement | groovy | 承認済み申込・受理済み見積・承認済み審査から契約を作成し、確定返済スケジュールを複製してローン口座を開設 |
| setLoanAgreementStatus | groovy | 契約のステータス遷移 |

- 返済計算ロジック：`groovyScripts/CreateLoanQuote.groovy`
  - 元利均等（annuity 公式）・元金均等の両方式に対応。`BigDecimal`（scale=2, HALF_UP）で算出。
  - 返済日は `UtilDateTime.adjustTimestamp(firstPaymentDate, Calendar.MONTH, n)` で月送り。
- 契約・口座開設ロジック：`groovyScripts/CreateLoanAgreement.groovy`
  - 契約前提条件は申込 `LOANAPP_APPROVED`、見積 `LOANQT_ACCEPTED`、同一申込の審査 `LOANUW_APPROVED`、見積期間分の完全な返済スケジュール。
  - 見積ステータスを契約処理内で暗黙変更せず、事前に `CREATED → PRESENTED → ACCEPTED` の正規遷移を必須とする。
  - 既存 `createFinAccount` で `LOAN_ACCOUNT` 口座を開設し、見積スケジュールを `LoanAgreementRepaymentSchedule` へ複製。
  - 契約を `DRAFT → ACTIVE`、申込を `APPROVED → CONTRACTED` へ各ステータスサービス経由で遷移。全処理は同一トランザクション。
- ステータス遷移（共通）：`groovyScripts/SetFinanceEntityStatus.groovy`
  - 渡された主キー（loanQuoteId / loanUnderwritingId / loanAgreementId）で対象を判別し、`StatusValidChange` で遷移を検証。
  - 審査承認 / 否決時は `decision`（APPROVED / REJECTED）と `reviewDate` も同期。

## 11. STEP 2 画面（`widget/FinanceStep2*.xml`）

| 画面 / URI | 内容 |
|-----------|------|
| `ManageLoanApplication` | 申込ごとの見積生成フォーム + 見積一覧 + 審査フォームを集約 |
| `ViewLoanQuote` | 見積ヘッダ + 返済スケジュール表示 |

- フォーム定義：`widget/FinanceStep2Forms.xml`、画面定義：`widget/FinanceStep2Screens.xml`
- 申込タブバー（`FinanceMenus.xml` の `LoanApplicationTabBar`）に「見積・審査管理」を追加。
- 見積一覧から `CREATED → PRESENTED → ACCEPTED / REJECTED` を正規遷移でき、契約作成リンクは受理済み見積だけに表示。
- 審査フォームから `IN_REVIEW → APPROVED / REJECTED` を実行可能。
- controller（`webapp/finance/WEB-INF/controller.xml`）に STEP 2 のリクエスト / ビューマッピングを追加。

## 12. STEP 2 検証状況

| 検証項目 | 方法 | 結果 |
|----------|------|------|
| entitymodel_step2 / services_step2 / UiLabels / component | 各対応 XSD でスキーマ検証 | TOTAL_ERRORS=0 |
| widget（Step2 Forms / Step2 Screens / Menus / controller） | widget XSD（HTTP include をローカル XSD へ解決）でスキーマ検証 | TOTAL_ERRORS=0 |
| Java / Groovy | `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| STEP 2 seed / テストデータ | XML 整形式チェック | OK |
| finance ライフサイクル実行テスト | `gradlew "ofbiz --test component=finance"` | tests=15 / errors=0 / failures=0（2026-10-09） |

実行テストでは、見積スケジュール計算、不正な見積直接遷移の拒否、審査承認、契約前提条件の拒否、契約 ACTIVE 化、申込 CONTRACTED 化、契約確定スケジュールの複製、および確定スケジュールからの請求・入金を確認している。

---

## 13. STEP 3 データモデル（`entitydef/entitymodel_step3.xml`）

契約後のライフサイクル（経理・請求・入金）。**既存の `Invoice` / `Payment` / `PaymentApplication` / `AcctgTrans` を再利用**し、finance 固有の対応・記録のみ新設。

### LoanInstallmentInvoice — 返済回 ↔ Invoice 対応
各返済回を請求書（Invoice）に紐づける。

| フィールド | 型 | 説明 |
|-----------|----|----|
| loanAgreementId (PK) | id | → LoanAgreement |
| installmentNumber (PK) | numeric | 回次 |
| invoiceId | id | → 既存 Invoice |
| loanQuoteId | id | → LoanQuote（スケジュール元） |
| dueDate | date-time | 返済日 |
| principalAmount / interestAmount / billedAmount | currency-amount | 元金 / 利息 / 請求合計 |
| accruedInterestAppliedAmount | currency-amount | 期日前の未収利息から請求へ実際に振り替えた額（跨月の二重収益防止） |

### LoanAccrual — 月次利息計上
各期間の未収利息（経過利息）を記録。

| フィールド | 型 | 説明 |
|-----------|----|----|
| loanAccrualId (PK) | id | 計上ID |
| loanAgreementId | id | → LoanAgreement |
| accrualDate / fromDate / thruDate | date-time | 計上日 / 期間 |
| outstandingPrincipal | currency-amount | 計上対象の残元本 |
| interestAmount | currency-amount | 当期の経過利息 |
| acctgTransId | id | → 既存 AcctgTrans（GL 仕訳、任意） |

### LoanPaymentReceipt — 入金 ↔ Payment 対応
受領した入金を記録。

| フィールド | 型 | 説明 |
|-----------|----|----|
| loanPaymentReceiptId (PK) | id | 入金受領ID |
| loanAgreementId | id | → LoanAgreement |
| paymentId | id | → 既存 Payment |
| invoiceId | id | 消し込んだ請求書（任意） |
| amount / currencyUomId / receiptDate | - | 金額 / 通貨 / 入金日 |

### STEP 3 の seed（`data/FinanceStep3TypeData.xml`）

- `InvoiceItemType` = `LOAN_PRINCIPAL`（元金）+ `InvoiceItemTypeMap`。利息はコアの `INV_INTRST_CHRG` を再利用。

---

## 14. STEP 3 サービス（`servicedef/services_step3.xml`、Java 実装）

業務ロジックは Java（`src/main/java/org/apache/ofbiz/finance/accounting/FinanceAccountingServices.java`）。精度・テスト容易性・パフォーマンスのため、計算・会計連携は Java に寄せた。

| サービス名 | エンジン | 説明 |
|-----------|---------|------|
| runMonthlyInterestAccrual | java | ACTIVE契約について、契約開始日・処理日・有効な入金消込による日次残元本を用いた Actual/365 で経過利息を計算。同一契約・月は冪等にし、未収利息（借方）/ 利息収益（貸方）の `AcctgTrans` を作成・転記して `LoanAccrual` に紐付け |
| runLoanMonthlyAccountingBatch | java | 全ACTIVE契約（または指定契約）を月次計上し、処理日当日の終端までに期日到来した契約確定返済回を一括請求。再実行時は計上・請求とも重複作成しない |
| createLoanInstallmentInvoice | java | 契約確定返済スケジュールを `createInvoice` + `createInvoiceItem` で請求化。元本はFinAccountの元本債権勘定、計上済み利息は未収利息、未計上分だけ利息収益へ振り替え、InvoiceをREADY化して請求GLを転記 |
| receiveLoanPayment | java | `createPayment` + `createPaymentApplication` で入金を請求に適用し、core入金・消込GLを転記して `LoanPaymentReceipt` に記録。累計入金が請求残額を超えないことを契約DBロック下で検証し、支払方法種別は未指定時 `EFT_ACCOUNT` |

- 残元本 = 契約元本 − 有効な `PaymentApplication` の入金済み元金。部分入金は利息優先で元金充当額を算定し、Payment取消・消込解除は次回計上へ自動反映する。
- 同月内で月中計上後に再実行した場合、直前 `thruDate` の翌日から指定日までを増分計上する。請求は期日前の全未消費未収利息を古い期間から振り替え、差額だけを当日利息収益にする。
- 月次計上は契約・開始日の一意制約、請求は契約・回次の主キーと Invoice 一意制約、入金記録は Payment 一意制約で重複永続化を防止。契約の `processingLockVersion` 条件付き更新で同時計上・請求・入金を直列化する。
- GL勘定は貸主組織の `GlAccountTypeDefault`、または組織に割り当てられた `ACCOUNTS_RECEIVABLE` / `INTRSTINC_RECEIVABLE` / `INTEREST_INCOME` 勘定から解決する。契約FinAccountの `postToGlAccountId` に元本債権勘定を固定する。
- 公開操作は `FINANCE_ACCOUNTING_CREATE` / `FINANCE_BILLING_CREATE` / `FINANCE_PAYMENT_CREATE` に分離し、バッチ・請求間の共有処理は非公開内部サービスを使用する。
- 既存 accounting サービス（`quickCreateAcctgTransAndEntries` / `postAcctgTrans` / `createInvoice` / `createInvoiceItem` / `createPayment` / `createPaymentApplication`）に委譲して GL・債権管理と連携。

### `plugins/finance` 外のcore修正

STEP 3 の入金消込解除を正しく成立させるため、financeプラグイン内だけでなく、次のOFBiz accounting coreファイルを変更している。

```text
applications/accounting/src/main/groovy/org/apache/ofbiz/accounting/payment/PaymentServices.groovy
```

対象は標準サービス `removePaymentApplication()` の、全額消込済みInvoiceを `INVOICE_PAID` から `INVOICE_READY` へ戻す処理である。

| 項目 | 内容 |
|------|------|
| 既存不具合 | `setInvoiceStatus` の入力名が `statustId` と誤記され、消込解除後もInvoiceがPAIDのまま残る可能性があった |
| 修正 | `statustId` を `statusId` に修正し、`paidDate: null` を明示してREADYへ戻す |
| エラー処理 | `setInvoiceStatus` の結果を確認し、エラー時は呼出元へ返してPaymentApplication削除を同一トランザクションでロールバックする |
| financeで必要な理由 | `LoanPaymentReceipt` の元となるPaymentApplicationを解除した際、Invoice状態・未消込残額・将来の残元本／利息計算を一致させるため |
| 影響範囲 | finance専用処理ではなく、標準 `removePaymentApplication` を利用するOFBiz全体。誤記修正のため、通常は既存の意図された動作へ戻す影響となる |
| 検証 | financeライフサイクルテストで、全額消込後の単独解除によりInvoiceがREADYへ戻り、残存適用額が復元されることを確認 |

この変更は **`plugins/finance` の配布物だけを差し替えても反映されない**。デプロイ・差分管理・レビューでは、finance側変更と上記core側変更をセットで扱う必要がある。core無変更を必須とする運用では、この修正をcoreから戻したうえで、finance専用の消込解除ラッパーサービス内に同等の状態復元とエラー処理を実装する代替設計が必要となる。汎用的な誤記修正なので、可能であればOFBiz本体へのupstream修正候補として管理する。

## 15. STEP 3 画面（`widget/FinanceStep3*.xml`）

| 画面 / URI | 内容 |
|-----------|------|
| `FindLoanAgreement` | 契約一覧と、全ACTIVE契約を対象にした月次経理・期日到来請求バッチ |
| `ManageLoanAgreement` | 契約ごとに「月次利息計上」「請求書作成」「入金受付」を集約（各一覧つき、計上一覧にはGL仕訳IDを表示） |

- アプリバー（`FinanceAppBar`）に「契約（Loan Agreement）」メニューを追加。
- controller に STEP 3 のリクエスト / ビューマッピングを追加。

## 16. STEP 3 単体テスト（`testdef/`）

OFBiz のテスト機構（`OFBizTestCase` 継承 + サービス実行）で、ローンのライフサイクルを検証。

- `testdef/financetests.xml` — テストスイート（`ofbiz-component.xml` に登録）。テストデータ投入 + テストクラス実行。
- `testdef/data/FinanceTestData.xml` — テスト用の Party / Product / FinancialProduct / LoanApplication。
- `src/main/groovy/org/apache/ofbiz/finance/test/LoanLifecycleTests.groovy` — 7 ケース:
  1. 元利均等見積：返済スケジュールの元金合計 = 元本、最終残高 = 0 を検証
  2. 元金均等見積：各回元金一定・返済額逓減、および未知返済方式の拒否を検証
  3. ワークフロー迂回防止：通常CRUDで申込・見積・審査ステータスを直接変更できず、提出・承認・契約後の申込条件と承認後の審査が不変であることを検証
  4. 見積・審査遷移：見積の直接受理を拒否し、提示後の受理と審査承認時の判定同期を検証
  5. 契約作成：申込・見積・審査の前提条件、`FinAccount` 開設、契約 ACTIVE / 申込 CONTRACTED、確定スケジュール複製、再送時の冪等性を検証
  6. 請求 → 入金：契約確定スケジュールからの請求、請求再送の冪等性、部分入金、累計超過拒否、他契約請求への誤消込拒否、非ACTIVE契約の請求・入金拒否を検証
  7. 月次経理・定期請求：Actual/365の日次残高利息、同日00:00指定での期日到来判定、月中→月末の非重複増分計上、前月未収利息の翌月請求振替、READY請求と利息収益の一回認識、借貸一致する転記済みGL仕訳、再実行重複防止を検証。入金ケースでは単独消込解除によるInvoice READY復元も検証

実行：`gradlew "ofbiz --test component=finance"`。

## 17. STEP 3 検証状況

| 検証項目 | 方法 | 結果 |
|----------|------|------|
| entitymodel_step3 / services_step3 / financetests / component / UiLabels | 各対応 XSD でスキーマ検証 | TOTAL_ERRORS=0 |
| widget（Step3 Forms / Step3 Screens / Menus / controller） | widget XSD（ローカル XSD 解決）でスキーマ検証 | TOTAL_ERRORS=0 |
| Java / Groovy | `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| STEP 3 seed / テストデータ | XML 整形式チェック | OK |
| finance ライフサイクル実行テスト | `gradlew "ofbiz --test component=finance"` | tests=15 / errors=0 / failures=0（2026-10-09） |

### 全STATUS・JPYデモデータ

`demo` readerには、通常のUI/PDF用マスタに加えて次の2ファイルを登録している。ロード順は為替レート → `FinanceDemoData.xml` の商品・申込者 → STATUSスナップショット。

| ファイル | 内容 |
|----------|------|
| `data/FinanceExchangeRateDemoData.xml` | 標準 `UomConversionDated` によるdemo専用のUSD/JPY双方向固定レート |
| `data/FinanceStatusDemoData.xml` | finance固有の全業務STATUSを各2件以上含むJPYスナップショット |

STATUSデモの範囲:

| エンティティ | STATUS |
|--------------|--------|
| LoanApplication | `RECEIVED` / `SUBMITTED` / `CANCELLED` / `REJECTED` / `APPROVED` / `CONTRACTED` |
| LoanQuote | `CREATED` / `PRESENTED` / `ACCEPTED` / `REJECTED` |
| LoanUnderwriting | `IN_REVIEW` / `APPROVED` / `REJECTED` |
| LoanAgreement | `DRAFT` / `ACTIVE` / `CLOSED` / `CANCELLED` |

- 各STATUSを最低2件用意し、すべての金額・契約通貨をJPYに統一。
- 契約STATUS 8件には、それぞれ一意な受理済み見積、承認済み審査、JPYの `FinAccount`、2回分の見積返済表と同一内容の契約確定返済表を付属させている。
- `LoanAccrual` / `LoanInstallmentInvoice` / `LoanPaymentReceipt` はfinance固有の `statusId` を持たないため、STATUS網羅対象には含めない。Invoice/Paymentの状態は実際のSTEP 3サービス実行で生成・更新する。
- STATUS行は画面確認用の完成済みスナップショットとしてEntity XMLから直接投入する。本番サービスの状態遷移制約を変更または迂回可能にするものではない。

デモ固定レート:

```text
1 USD = 150 JPY
USD → JPY: conversionFactor = 150.000000000000
JPY → USD: conversionFactor = 0.006666666667
有効開始日: 2020-01-01
```

これは市場レートではなく再現可能なデモ用仮定値であり、`seed`ではなく `demo` readerにだけ登録する。ただし `UomConversionDated` 自体はコンポーネントや組織で分離されないOFBiz共通エンティティなので、投入後はfinance以外を含む同一OFBizインスタンス全体のUSD/JPY換算に影響する。同じ通貨ペアで開始日が新しい有効レートが存在すれば、標準 `convertUom` は新しいレートを優先する。

投入例（OFBiz停止中）:

```powershell
.\gradlew.bat "ofbiz --load-data readers=demo --load-data component=finance"
```

2026-10-09の投入検証では、為替レート2件、既存UI/PDF用マスタ5件、STATUSスナップショット118件を処理し、合計125行の変更で `BUILD SUCCESSFUL`。投入後のfinance全テストも `tests=15 / errors=0 / failures=0` を確認している。

本番環境にはこのdemo readerを投入せず、実運用の為替レート管理を使用すること。

---

## 18. 残作業・将来拡張

- 入力バリデーションの一括追加（`type-validate` + サービス実装内の業務ルールチェック）。現状は主要な契約前提条件と型 / DB 制約を実装済み。
- 多通貨ローンの入金時に利用する為替レート・換算方針（単通貨テストは貸主基準通貨 USD で実施）。
- 延滞管理・督促、繰上返済・条件変更などのライフサイクルイベント。
- LTV の担保種別別ヘアカット（担保価額の掛目）や複数担保の優先順位管理。
- 与信枠のリボルビング化（引き出し・返済による利用可能残高の増減）。

---

## 19. 見積書 PDF 出力機能（STEP 2 拡張）

見積（`LoanQuote`）とその返済スケジュール（`LoanRepaymentSchedule`）を、**見積書 PDF** として出力する。
PDF 生成は独立した `jasperreports` プラグイン（JasperReports 7.0.8 の汎用 view handler）に委譲し、
finance 側はデータ準備とレポートテンプレートのみを持つ。

### 設計方針

| 項目 | 決定内容 |
|------|----------|
| PDF エンジン | `plugins/jasperreports` の汎用 JasperReports view handler（`type="jasperreports"`） |
| データ供給 | **DB 物理名に非依存**。JRXML に SQL を書かず、Groovy イベントがエンティティ API で取得したデータを `JRDataSource` + パラメータ Map で渡す |
| ヘッダ値 | パラメータ Map（`jrParameters` リクエスト属性）で渡す |
| 明細（返済スケジュール） | `JRMapCollectionDataSource`（`jrDataSource` リクエスト属性）で渡す |
| 日本語 | OpenPDF 組み込み CJK フォント（`HeiseiKakuGo-W5` / `UniJIS-UCS2-H`）を JRXML のデフォルトスタイルで指定し PDF 埋め込み |

### 構成ファイル

```
plugins/finance/
├── groovyScripts/
│   └── PrepareLoanQuoteReport.groovy      # controller イベント：見積データを取得し JRDataSource/パラメータを準備
├── webapp/finance/reports/
│   └── LoanQuoteReport.jrxml              # 見積書テンプレート（JasperReports 7.x 形式）
├── widget/FinanceStep2Screens.xml         # ViewLoanQuote に PDF 出力リンクを追加
├── config/FinanceUiLabels.xml             # 見積書・見積ヘッダ用ラベル（en / ja）
└── data/FinanceDemoData.xml               # デモ用ローン商品・申込者・申込（UI / PDF 動作確認用）

plugins/jasperreports/                     # 汎用 PDF レポート基盤（別プラグイン）
├── src/main/java/.../webapp/view/JasperReportsViewHandler.java  # jrDataSource/jrParameters 対応に拡張
├── build.gradle                           # jasperreports 7.0.8 + jasperreports-pdf + openpdf-fonts-extra
```

### データ準備イベント（`groovyScripts/PrepareLoanQuoteReport.groovy`）

実行時評価の controller イベント（`groovyScripts/` 配下なのでコンパイル時に JasperReports 依存は不要。
クラスは実行時にロード済みの jasperreports プラグインから解決される）。

1. `loanQuoteId`（リクエストパラメータ）で `LoanQuote` を取得
2. 関連を経由してヘッダ値を収集：
   - `LoanApplication`（`getRelatedOne`）→ `applicantPartyId` → `PartyHelper.getPartyName()` で借主名
   - `Enumeration`（`getRelatedOne`）→ 返済方法の表示名
   - 元本・年利・期間・毎月返済額・総返済額・利息合計・初回返済日・作成日
3. ヘッダ値を `Map`、返済スケジュール行を `List<Map>` → `JRMapCollectionDataSource` に変換
4. `request.setAttribute("jrParameters", map)` / `request.setAttribute("jrDataSource", dataSource)` で view handler へ受け渡し
5. `return "success"`

注意：`numeric` 型フィールド（`termMonths` / `installmentNumber`）は Java の `Long` にマップされるため、
`getBigDecimal()` ではなく値を取得して `BigDecimal` に変換する（`getBigDecimal` だと `ClassCastException`）。

### レポートテンプレート（`webapp/finance/reports/LoanQuoteReport.jrxml`）

- JasperReports 7.x ネイティブ JRXML 形式（`xmlns` / 外部スキーマ参照なし、`<element kind="...">` 構文）。
- `title`：見積書見出し + 見積番号 / 日付 / 貸主 / 借主 + 見積条件サマリ。
- `columnHeader` + `detail`：返済スケジュール表（回次 / 返済日 / 返済額 / 元金 / 利息 / 残高）。
- ヘッダ値は `$P{...}`、明細は `$F{...}`。`whenNoDataType="AllSectionsNoDetail"` で明細 0 件でも非 detail を出力。
- 日本語：デフォルトスタイルで `default="true" pdfFontName="HeiseiKakuGo-W5" pdfEncoding="UniJIS-UCS2-H" pdfEmbedded="true"`。
  （7.x 形式では `isDefault` ではなく `default` 属性）

### controller（`webapp/finance/WEB-INF/controller.xml`）

```xml
<!-- jasperreports プラグインの view handler を finance でも使えるよう登録 -->
<handler name="jasperreports" type="view"
        class="org.apache.ofbiz.jasperreports.webapp.view.JasperReportsViewHandler"/>

<!-- PDF リクエスト（uri を .pdf で終える OFBiz 慣習に従う） -->
<request-map uri="PrintLoanQuote.pdf">
    <security https="true" auth="true"/>
    <event type="groovy" path="component://finance/groovyScripts/PrepareLoanQuoteReport.groovy"/>
    <response name="success" type="view" value="LoanQuotePdf"/>
    <response name="error" type="view" value="ViewLoanQuote"/>
</request-map>

<view-map name="LoanQuotePdf" type="jasperreports"
        page="component://finance/webapp/finance/reports/LoanQuoteReport.jrxml"
        content-type="application/pdf"/>
```

### 画面リンク（`widget/FinanceStep2Screens.xml` の `ViewLoanQuote`）

```xml
<link target="PrintLoanQuote.pdf" text="${uiLabelMap.FinancePrintLoanQuotePdf}" style="buttontext pdf">
    <parameter param-name="loanQuoteId" from-field="loanQuote.loanQuoteId"/>
</link>
```

- `target-window="_BLANK"`（別タブ）は使わない。別タブだとリンクの href が機能せず空タブになったため、
  **同一ウィンドウ遷移**にしてある（ブラウザが PDF をインライン表示 / ダウンロードする）。
- リンクには `from-field` で `loanQuoteId` を明示的に渡す。

### jasperreports view handler の拡張（別プラグイン）

汎用 view handler に以下の経路を追加（既存の JDBC fill は後方互換で維持）：

- リクエスト属性 `jrDataSource`（`JRDataSource`）があれば、JDBC 接続の代わりにそれで `fillReport`。
- リクエスト属性 `jrParameters`（`Map`）をレポートパラメータにマージ（リクエストパラメータより優先）。

これにより JRXML に DB 固有の SQL を書かずに、OFBiz エンティティ API で用意したデータで PDF 化できる。

### 依存（`plugins/jasperreports/build.gradle`）

JasperReports 7.x は機能がアーティファクト分割されている点に注意：

- `net.sf.jasperreports:jasperreports:7.0.8` — コアエンジン
- `net.sf.jasperreports:jasperreports-pdf:7.0.8` — **PDF エクスポートは別アーティファクト**
  （無いと `Missing JasperReports PDF Extension`）。Jaspersoft パッチ版 OpenPDF に依存するため OpenPDF は exclude しない。
- `com.github.librepdf:openpdf-fonts-extra:1.3.43` — 日本語 CJK 組み込みフォント（`HeiseiKakuGo-W5` ほか）のリソース

### 動作確認手順

1. デモデータ投入（OFBiz 停止中に実行。`--load-data` は引数ごとに繰り返す）：
   `gradlew "ofbiz --load-data readers=seed,demo --load-data component=finance"`
2. OFBiz 起動 → finance にログイン（`admin` は `FULLADMIN` 所属。seed で `FINANCE_ADMIN` を `FULLADMIN` にも付与済み）
3. `ManageLoanApplication?loanApplicationId=DEMO_LOANAPP1` で見積生成
4. 見積一覧の見積 ID から `ViewLoanQuote` を開く
5. 「見積書を印刷 (PDF)」→ 見積ヘッダ + 返済スケジュール表の PDF が同一ウィンドウで表示される（日本語含む）

### JasperReports 7.x の注意点（実装で遭遇した差分）

- JRXML は Jackson ベースの新パーサ。6.x 形式（`xmlns` / `xsi:schemaLocation`、`<band>` ラッパー、`<staticText>` /
  `<reportElement>`）は不可。外部 DTD/スキーマ参照はデフォルト禁止（`xml.allow.doctype=false`）。
- 単一バンドセクション（`title` / `pageFooter`）は直下に `<element kind="...">` を置き、自身に `height` 属性。
  複数バンドの `detail` のみ `<band>` ラッパーを使う。
- 未知属性はパーサが「known properties」を列挙してくれるので、それに合わせて修正できる
  （例：style は `isDefault` ではなく `default`）。

### 検証状況

| 検証項目 | 方法 | 結果 |
|----------|------|------|
| 見積書 PDF の生成（ヘッダ + 返済スケジュール） | OFBiz 起動 → `PrintLoanQuote.pdf?loanQuoteId=...` | PDF 出力 OK |
| データ取得（借主名含む） | Groovy イベントのデバッグログで確認 | `borrowerName` 取得 OK |
| 日本語表示 | PDF を目視確認 | 文字化けなく表示 OK |
| 画面リンク | `ViewLoanQuote` の PDF リンク | 同一ウィンドウで PDF 表示 OK |

---

## 20. STEP 4 担保付ローン（`entitydef/entitymodel_step4.xml`）

住宅ローン等の担保付ローンを、**申込（申告）→ 審査（評価）→ 契約（抵当権設定）** の段階で管理する。
担保物件は finance 側 `Collateral` エンティティとして保持し、任意で既存 `FixedAsset`（不動産等）を参照するハイブリッド方式。

### データモデル

| エンティティ | 主キー | 主要フィールド / 関連 | 役割 |
|---|---|---|---|
| `CollateralType` | collateralTypeId | parentTypeId, hasTable, description | 担保種別（REAL_ESTATE / VEHICLE / DEPOSIT / SECURITIES） |
| `Collateral` | collateralId | collateralTypeId, ownerPartyId→Party, fixedAssetId→FixedAsset(任意), currencyUomId, description, statusId→StatusItem | 担保物件マスタ |
| `LoanApplicationCollateral` | loanApplicationId + collateralId | declaredValueAmount(任意), comments | 申込への担保申告 |
| `CollateralValuation` | collateralValuationId（採番） | collateralId, valuationDate, appraisedValueAmount, valuationMethodEnumId, appraiserPartyId ＋一意Index(collateralId, valuationDate) | 担保評価（最新評価が LTV 正本） |
| `LoanCollateral` | loanAgreementId + collateralId | fromDate, thruDate(NULL=設定中), assignedValueAmount, lienDescription | 契約への抵当権設定 |

- `FinancialProduct` に `maxLoanToValueRatio`（`fixed-point`、例 0.800000=80%、NULL=制限なし）を追加（加法的変更）。
- seed（`FinanceStep4TypeData.xml`）: `CollateralType` 4件、`EnumerationType`=`COLL_VAL_METHOD`、`StatusType`=`COLLATERAL_STATUS`（`COLL_REGISTERED`→`COLL_RELEASED`）。

### サービス（`servicedef/services_step4.xml` ＋ Groovy）

| サービス | 説明 |
|---|---|
| createCollateral / updateCollateral / deleteCollateral | 担保 CRUD（作成時 COLL_REGISTERED 固定、解除後は変更不可、未紐付けのみ削除可） |
| setCollateralStatus | StatusValidChange 検証付きステータス遷移 |
| assignLoanApplicationCollateral / removeLoanApplicationCollateral | 申込への担保申告（RECEIVED 中のみ） |
| createCollateralValuation | 評価の記録 |
| releaseLoanCollateral | 契約紐付け解除（thruDate 設定） |

内部サービス（`export="false"`、承認・契約で共用）:
- `financeValidateCollateralLtv`: 商品が担保必須なら申告担保と評価を必須とし、`元本 ÷ Σ最新評価額 ≤ maxLoanToValueRatio` を検証。
- `financeCreateLoanCollateralLinks`: 申告担保ごとに `LoanCollateral` を生成（`assignedValueAmount`=最新評価額、冪等）。

### 既存サービスの拡張

- `SetFinanceEntityStatus.groovy`: 審査承認（`LOANUW_APPROVED`）時に LTV を検証し、超過時は承認を拒否。
- `CreateLoanAgreement.groovy`: 契約作成時に LTV を再検証し、申告担保を `LoanCollateral` へ確定。

### 画面

- `FindCollateral` / `EditCollateral`（担保一覧・登録編集、評価の記録）
- `ManageLoanApplication` に「担保申告」セクション、`ManageLoanAgreement` に「紐付け済み担保」セクションを追加。

### テスト（`LoanCollateralTests.groovy`）

担保 CRUD と解除後の不変性、申告の RECEIVED 制約、評価記録、担保必須の拒否、LTV 超過の拒否、契約時の紐付け・解除の 6 ケース。

### 検証状況

| 検証項目 | 方法 | 結果 |
|----------|------|------|
| entitymodel_step4 / services_step4 / UiLabels / component / widget | 各対応 XSD でスキーマ検証 | TOTAL_ERRORS=0 |
| Java / Groovy | `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| finance ライフサイクル実行テスト | `gradlew "ofbiz --test component=finance"` | errors=0 / failures=0 |

---

## 21. STEP 5 事業者向けローン（`entitydef/entitymodel_step5.xml`）

事業者（組織 Party）向けローンを実装する。既存 `party` の組織 Party をそのまま借主として利用し、
事業者固有の「与信枠（限度額チェックのみ）」と「保証人（任意記録）」を追加する。

### データモデル

| エンティティ | 主キー | 主要フィールド / 関連 | 役割 |
|---|---|---|---|
| `CreditLineType` | creditLineTypeId | parentTypeId, hasTable, description | 与信枠種別（REVOLVING / NON_REVOLVING） |
| `CreditLine` | creditLineId（採番） | creditLineTypeId, partyId→Party, currencyUomId, fromDate/thruDate, creditLimitAmount, description | 事業者への与信枠 |
| `LoanGuarantor` | loanApplicationId + guarantorPartyId | guarantorPartyId→Party, guaranteeTypeEnumId, guaranteeAmount, currencyUomId, comments | 申込に紐づく保証人（任意） |

- seed（`FinanceStep5TypeData.xml`）: `CreditLineType` 2件、`EnumerationType`=`LOAN_GUARANTEE_TYPE`（連帯/物上/単純保証）。

### サービス（`servicedef/services_step5.xml` ＋ Groovy）

| サービス | 説明 |
|---|---|
| createCreditLine / updateCreditLine / deleteCreditLine | 与信枠 CRUD（作成は採番・fromDate デフォルト現在日） |
| assignLoanGuarantor / removeLoanGuarantor | 保証人の記録・削除（RECEIVED 中のみ、任意） |

内部サービス（`export="false"`）:
- `financeValidateCreditLimit`: 申込者の有効な与信枠（通貨一致・fromDate/thruDate 有効）を検出し、
  `既存 ACTIVE 契約の元本合計 + 新規元本 ≤ 限度額` を検証。与信枠が無ければスキップ。
  ※単純チェックであり、返済による残高減は考慮しない。

### 既存サービスの拡張

- `SetFinanceEntityStatus.groovy`: 審査承認時に与信限度額を検証。
- `CreateLoanAgreement.groovy`: 契約作成時に与信限度額を再検証。

### 画面

- `FindCreditLine` / `EditCreditLine`（与信枠一覧・登録編集）
- `ManageLoanApplication` に「保証人」セクションを追加。

### テスト（`LoanBusinessLoanTests.groovy`）

与信枠 CRUD、保証人の記録・削除（RECEIVED 制約）、限度額超過の拒否、限度額内の承認、既存与信の考慮の 5 ケース。

### 検証状況

| 検証項目 | 方法 | 結果 |
|----------|------|------|
| entitymodel_step5 / services_step5 / UiLabels / component / widget | 各対応 XSD でスキーマ検証 | TOTAL_ERRORS=0 |
| Java / Groovy | `gradlew compileJava compileGroovy` | BUILD SUCCESSFUL |
| finance ライフサイクル実行テスト | `gradlew "ofbiz --test component=finance"` | errors=0 / failures=0 |

---

### 運用メモ

- 新しい seed（型・ステータス・権限）を追加した場合は、**OFBiz クリーン再起動（または全キャッシュクリア）＋再ログイン** で DB・キャッシュに反映する。
  STEP 1 で、権限 seed 投入後に権限キャッシュが古く「このアプリケーションは使用できません」となる事象を確認済み。
- Java サービスを追加・変更した場合は再ビルド（`classes` タスク）が必要。
- STEP 3 の消込解除修正は `plugins/finance` 外の `applications/accounting/src/main/groovy/org/apache/ofbiz/accounting/payment/PaymentServices.groovy` を含む。financeプラグインだけをコピーするデプロイでは欠落するため、core差分も同時に適用して `compileGroovy` とfinanceテストを実行する。
- `gradlew loadDefault` による全データ再投入は未実施。finance の実行テストは `gradlew "ofbiz --test component=finance"` で完了し、tests=35 / errors=0 / failures=0 を確認済み。
- 権限：webapp の `base-permission="OFBTOOLS,FINANCE"` を満たすため、`FINANCE_ADMIN` を `SUPER` だけでなく
  `FULLADMIN` / `FLEXADMIN` にも付与している（デモの `admin` は `FULLADMIN` 所属で `SUPER` 非所属のため）。
- デモデータ：`data/FinanceDemoData.xml`（商品・申込者）、`data/FinanceStep4DemoData.xml`（担保付商品・担保・申告）、`data/FinanceStep5DemoData.xml`（事業者商品・与信枠・保証人）、`data/FinanceStatusDemoData.xml`（全業務STATUSのJPYスナップショット）、`data/FinanceExchangeRateDemoData.xml`（demo専用USD/JPY固定レート）をdemo readerに登録している。UIでローン商品プルダウンが空になる場合はデモデータ未投入が原因。投入は
  `gradlew "ofbiz --load-data readers=demo --load-data component=finance"`（OFBiz停止中、`--load-data` は引数ごとに繰り返す）。為替レートはOFBiz共通エンティティへ入り、同一インスタンス全体に影響するため、本番へは投入しない。
- 見積書 PDF の日本語：`jasperreports` プラグインに `openpdf-fonts-extra` が必要。JRXML のデフォルトスタイルで
  CJK 組み込みフォント（`HeiseiKakuGo-W5` / `UniJIS-UCS2-H`）を指定している。フォントが無いと日本語が
  （□にもならず）空白で欠落する。
- 申込者ルックアップ：`EditLoanApplication` の申込者選択は `component://party/.../LookupScreens.xml#LookupPartyName`
  を使うため、finance controller に `LookupPartyName` の request-map / view-map を登録している。

