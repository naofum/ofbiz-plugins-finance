# AGENTS.md

## Project Context
- Apache OFBiz Webアプリケーション
- The Data Model Resource BookのFinance Servicesモデルに準拠する
- 開発対象のfinance機能は `plugins/finance/` にある。
- 要件定義は `finance/REQUIREMENTS.md`、financeの詳細仕様・運用記録は `finance/README.md` を正とする。
- Windows環境では `gradlew.bat` を使用する。

## Finance Domain Rules
- LoanApplicationは作成時に必ず `LOANAPP_RECEIVED`。内容更新はRECEIVED中だけに限定し、状態変更は専用ステータス遷移サービスのみを使用する。
- LoanQuoteは生成後に不変。条件変更は更新せず新規見積を作成する。請求は見積案でなく `LoanAgreementRepaymentSchedule`（契約確定返済表）だけを参照する。
- LoanUnderwritingは `LOANUW_IN_REVIEW` で作成し、承認・否決後は変更不可。承認・否決は状態、decision、reviewDateを一貫して確定する。
- 契約作成には、承認済み申込、受理済み見積、承認済み審査、完全な返済表が必要。契約作成時に返済表をスナップショットし、同一見積からの契約は1件だけとする。
- entity-auto等の通常CRUDで業務STATUSを変更してはならない。対応する状態遷移サービスを使用する。

## Finance Accounting Rules
- STEP 3は既存の `Invoice`、`Payment`、`PaymentApplication`、`AcctgTrans` を再利用する。
- 月次利息はActual/365の日次残元本方式で計算する。月中再実行は直前計上期間の翌日から増分計上する。
- 月次バッチは期日順に「期日まで利息計上 → 請求・未収利息振替 → 処理日まで残期間の増分計上」を行い、利息収益を二重計上しない。
- 請求はInvoiceをREADY化し、元本債権・未収利息・利息収益・売掛金のGL整合を保つ。入金・消込のcore GL仕訳も転記する。
- 残元本は、有効なPaymentApplicationと `PMNT_RECEIVED` / `PMNT_CONFIRMED` のPaymentを正本として利息優先で算定する。請求しただけでは元本を減額しない。
- `LoanAgreement.processingLockVersion` による条件付き更新で、同一契約の計上・請求・入金を直列化する。

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
- demoデータを変更した場合は、必要に応じて次を実行して実ロードを確認する。

  ```powershell
  .\gradlew.bat "ofbiz --load-data readers=demo --load-data component=finance"
  ```

## Change Boundaries
- finance機能は原則として `plugins/finance/` に実装する。
- core変更が必要な場合は、理由、影響範囲、代替案、検証結果を `plugins/finance/README.md` に記録する。
- 現在、消込解除の整合性のため次のcore修正が含まれる。

  ```text
  applications/accounting/src/main/groovy/org/apache/ofbiz/accounting/payment/PaymentServices.groovy
  ```

  `removePaymentApplication()` の `statustId` 誤記を `statusId` へ修正し、PAID InvoiceをREADYへ戻してpaidDateをクリアし、遷移エラーを伝播する。financeプラグイン単体の配布には含まれないため、デプロイ時はcore差分も同時に扱う。

## Repository and Database Safety
- この作業ツリーではGitメタデータが利用できない場合がある。`git diff`を前提にせず、対象ファイル、実ロード、コンパイル、テスト結果で検証する。
- 旧テストDBテーブルや反復開発で残った列は、明示的な依頼なしに削除しない。
- 既知の旧テーブル・列に関する警告は、現行entity、データロード、financeテストへの影響を確認してから扱う。
