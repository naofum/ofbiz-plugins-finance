# AGENTS.md

## Project Context
- Apache OFBiz Webアプリケーション
- The Data Model Resource BookのFinance Servicesモデルに準拠する
- 開発対象のfinance機能は `plugins/finance/` にある。
- 要件定義は `REQUIREMENTS.md`（ローン）と `REQUIREMENTS_LEASE.md`（共通層・リース、STEP L0〜）、詳細仕様・運用記録は `SPEC_COMMON.md`（共通層）・`SPEC.md`（ローン層）・`SPEC_LEASE.md`（リース層）を正とする。
- Windows環境では `gradlew.bat` を使用する。
- リース業務は同一プラグイン内に追加する。プラグイン内は共通層・ローン層・リース層に分け、依存は「ローン層→共通層」「リース層→共通層」のみとする。ローン層とリース層は互いを参照しない（詳細は `REQUIREMENTS_LEASE.md` 1.2）。
- 層とパッケージ：共通層 `org.apache.ofbiz.finance.common`、ローン層 `org.apache.ofbiz.finance.loan`、リース層 `org.apache.ofbiz.finance.lease`。共通層のJavaはローン層・リース層のクラスをimportしない（契約種別ごとの処理は `FinanceContractAdapter` 経由。実装クラスは `finance.properties` の `contract.adapter.<contractTypeId>` で登録する）。

## Common Layer Rules（共通層）
- ローン契約・リース契約は、作成時に必ず共通契約台帳 `FinanceContract` を1件作成し、各層の契約から `financeContractId` で1:1に参照する（`FinanceContractHelper.createContract`）。
- `FinanceContract.statusId`（`FINCTR_*`）は各層の契約状態の写しである。各層の状態遷移サービスの中でだけ `FinanceContractHelper.updateStatus` により更新し、CRUDで直接変更しない。
- 同一契約の計上・請求・入金・延滞処理は、`FinanceContract.processingLockVersion` の条件付き更新（`FinanceContractHelper.lock`）で直列化する。`LoanAgreement.processingLockVersion` は廃止済みで使用しない。
- 与信判定は、同一顧客の `FINCTR_CONTRACTED` / `FINCTR_ACTIVE` の `FinanceContract`（ローン・リース合算）の元本で行う（`FinanceContractHelper.sumExposurePrincipal`、与信枠チェックは共通の `financeValidateCustomerCreditLimit`）。
- 延滞判定・遅延損害金・督促・償却は共通層の `FinanceDelinquencyServices` が `FinanceContract` を起点に処理する。延滞関連レコードには `financeContractId` を必ず設定する（`loanAgreementId` は互換用）。契約種別ごとの延滞額（元本・利息・消費税）、遅延損害金の計算基礎、償却の貸方科目は `FinanceContractAdapter` が返す（リースは `LeaseContractAdapter`、計算基礎は `lease.latefee.base`）。
- 審査は `LOANUW_IN_REVIEW` で作成し、承認・否決後は変更不可。承認・否決は状態、decision、reviewDateを一貫して確定する。審査には `applicationTypeId`（LOAN/LEASE）と `applicationId` を設定する。
- entity-auto等の通常CRUDで業務STATUSを変更してはならない。対応する状態遷移サービスを使用する（全層共通）。
- 人が行う作業（満了時の返還・引取り、督促活動、保守作業 等）は core の `WorkEffort` でタスク化し、共通層の `FinanceContractWorkEffort` で契約に紐付ける（STEP L7〜、`plugins/finance/REQUIREMENTS_LEASE.md` 3.5）。WorkEffort の状態は作業の完了だけを表し、業務STATUSを変えない（業務サービスの結果としてタスクを完了にする）。タスクの自動作成は契約×作業種別×きっかけごとに1件（冪等）。バッチの定期実行は WorkEffort ではなくジョブスケジューラで行う。
- 契約の取引先（役割・持分・期間）は共通層の `FinanceContractParty`、基本契約と個別契約の関係は `FinanceContract.parentFinanceContractId` で表す（STEP L10a、`plugins/finance/SPEC_COMMON.md` 10 章）。契約作成時は `FinanceContractHelper.createContract` の貸手組織つきの版を使い、顧客と貸手組織を 100% で登録する。役割は `FIN_CTR_ROLE` 配下の `RoleType` だけを使い、契約種別ごとの役割は `FinanceContractAdapter` が返す。既存の業務処理（請求・与信・延滞・税）は各層の取引先列を読み続け、土台を読むように切り替えるときは、その STEP の仕様書に記録する。

## Loan Layer Rules（ローン層）
- LoanApplicationは作成時に必ず `LOANAPP_RECEIVED`。内容更新はRECEIVED中だけに限定し、状態変更は専用ステータス遷移サービスのみを使用する。
- LoanQuoteは生成後に不変。条件変更は更新せず新規見積を作成する。請求は見積案でなく `LoanAgreementRepaymentSchedule`（契約確定返済表）だけを参照する。
- 契約作成には、承認済み申込、受理済み見積、承認済み審査、完全な返済表が必要。契約作成時に返済表をスナップショットし、同一見積からの契約は1件だけとする。

## Loan Accounting Rules（ローン層）
- STEP 3は既存の `Invoice`、`Payment`、`PaymentApplication`、`AcctgTrans` を再利用する。
- 月次利息は設定された日割り基準（既定Actual/365）の日次残元本方式で計算する。月中再実行は直前計上期間の翌日から増分計上する。
- 月次バッチは期日順に「期日まで利息計上 → 請求・未収利息振替 → 処理日まで残期間の増分計上」を行い、利息収益を二重計上しない。
- 請求はInvoiceをREADY化し、元本債権・未収利息・利息収益・売掛金のGL整合を保つ。入金・消込のcore GL仕訳も転記する。
- 残元本は、有効なPaymentApplicationと `PMNT_RECEIVED` / `PMNT_CONFIRMED` のPaymentを正本として利息優先で算定する。請求しただけでは元本を減額しない。

## Lease Layer Rules（リース層）
- 詳細は `REQUIREMENTS_LEASE.md` を正とする。リース申込は `LeaseApplication`（作成時 `LEASEAPP_RECEIVED`）、見積は生成後不変、請求は契約確定リース料表のみを参照する。
- リース商品は `LEASE` 配下の `FinancialProductType` と `FinancialProductLease`（1:1）で定義する。リース区分は商品種別から導出し、不一致を許さない。
- リース申込の内容・希望物件（`LeaseApplicationAsset`）の変更は `LEASEAPP_RECEIVED` 中だけ。希望物件のない申込は、申込・審査とも承認できない。
- リースの審査は共通審査（`createLeaseUnderwriting` → `createFinanceUnderwriting`、`applicationTypeId=LEASE`）を使い、承認時の検証は `finance.properties` の `underwriting.approval.validator.LEASE` に登録する。
- リース見積は生成後不変（取得価額＝希望物件の見積額合計）。リース契約は `LEASEAGR_CONTRACTED` で作成し、`ACTIVE` への遷移は検収（`acceptLeaseAgreement`）でのみ行う。検収日がリース開始日で、確定リース料表を新しい revision に作り直し、開始仕訳を契約ごとに 1 回だけ計上する。物件購入後の契約取消は不可。
- 収益計上はファイナンスリース第1法（リース取引開始日に売上高・売上原価を計上し、利息相当額を繰延リース利益として利息法で戻し入れる）を主とする。
- リースの請求・繰延リース利益の戻入・入金は検収済み（`LEASEAGR_ACTIVE`）の契約だけで行い、請求は現在の revision の確定リース料表の回ごとに 1 件（冪等）とする。請求時に売上は計上しない（借 売掛金 ／ 貸 リース投資資産・リース債権・仮受消費税）。
- 繰延リース利益の戻入は「累計戻入額（計上日まで）− 計上済み累計」で増分計上し、二重計上しない。満了時の累計は開始時の繰延リース利益と一致させる。方式ごとの仕訳は `LeaseRevenueStrategy` に置く。
- リースの未回収元本は、有効な PaymentApplication と `PMNT_RECEIVED` / `PMNT_CONFIRMED` の Payment を正本とし、請求書ごとに消費税 → 利息相当額 → 元本回収額の順に充当して算定する。請求しただけでは減額しない。
- リースの消費税率は検収日（リース開始日）に有効な core `TaxAuthorityRateProduct`（商品の税カテゴリ）で契約に固定し、請求日の税率は使わない。計上時期（各回／開始時一括、`LEASE_TAX_TIMING`）も検収時に固定する。端数は通貨別端数処理で、開始時一括は総額を 1 回丸めて最終回で調整する。
- 貸手所有物件の公租公課（償却資産税・自動車税・重量税 等）は `LeasePublicCharge*` で扱う（STEP L10b、`SPEC_LEASE.md` 12 章）。税目ごとの規則（計算方法・課税団体・賦課期日・免税点・GL 科目）は `LeasePublicChargeType`、物件種別ごとの対象税目は `LeasePublicChargeAssetType`、計算方式は `LeasePublicChargeCalculator` に置く。申告は物件の所有持分（`LeaseAgreementAsset.ownershipShare`）で按分する。旧 `LeasePropertyTax*` は移行（`migrateLeasePropertyTaxData`）のためだけに残しており、業務ロジックで使わない。

## Data Rules
- `data/*TypeData.xml` はseed、`data/*DemoData.xml` はdemo、`testdef/data/*` はテスト専用。用途を混在させない。
- STATUSデモは完成済み業務スナップショットであり、本番サービスの状態遷移制約を緩和しない。
- `FinanceExchangeRateDemoData.xml` のUSD/JPY固定レートはdemo reader専用。`UomConversionDated` はOFBiz全体で共有されるため、本番環境へdemo readerを投入しない。
- デモ投入時は、為替レートが同一インスタンス全体へ影響することを確認する。

## Validation
- XML変更時は整形式を確認する。
- Java/Groovy変更時は次を実行する。

  ```powershell
  .\gradlew.bat compileJava compileGroovy
  .\gradlew.bat "ofbiz --test component=finance"
  ```

- テスト前に通常のOFBizが停止していることを確認する。テスト用OFBizはコマンドが起動・停止する。
- テスト結果は `runtime/logs/test-results/financetests.xml` で `errors="0"` と `failures="0"` を確認する。
- テスト実行を途中で中断すると、テスト用データが一部残り次回のテストが失敗することがある（例：`TEST_LOANAPP_AGR` の承認済み審査）。失敗時は残存データを確認する（詳細は `SPEC_COMMON.md` 運用メモ）。
- 新しいseedファイルを追加・登録した場合は、テスト前に `.\gradlew.bat "ofbiz --load-data readers=seed --load-data component=finance"` でseedを投入する（テスト用delegatorは既定DBを共有する）。
- demoデータを変更した場合は、必要に応じて次を実行して実ロードを確認する。

  ```powershell
  .\gradlew.bat "ofbiz --load-data readers=demo --load-data component=finance"
  ```

## Change Boundaries
- finance機能は原則として `plugins/finance/` に実装する。
- core変更が必要な場合は、理由、影響範囲、代替案、検証結果を、その変更を必要とした層の仕様書（共通層は `SPEC_COMMON.md`、ローン層は `SPEC.md`、リース層は `SPEC_LEASE.md`）に記録する。
- 現在、消込解除の整合性のため次のcore修正が含まれる。

  ```text
  applications/accounting/src/main/groovy/org/apache/ofbiz/accounting/payment/PaymentServices.groovy
  ```

  `removePaymentApplication()` の `statustId` 誤記を `statusId` へ修正し、PAID InvoiceをREADYへ戻してpaidDateをクリアし、遷移エラーを伝播する。financeプラグイン単体の配布には含まれないため、デプロイ時はcore差分も同時に扱う。

## Repository and Database Safety
- この作業ツリーではGitメタデータが利用できない場合がある。`git diff`を前提にせず、対象ファイル、実ロード、コンパイル、テスト結果で検証する。
- 旧テストDBテーブルや反復開発で残った列は、明示的な依頼なしに削除しない。
- 既知の旧テーブル・列に関する警告は、現行entity、データロード、financeテストへの影響を確認してから扱う。
