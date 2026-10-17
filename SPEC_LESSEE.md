# Apache OFBiz Lessee（リース借手）仕様書

`REQUIREMENTS_LESSEE.md` の要件に対する設計。各 STEP の実装後に「実装」「テスト」「検証」を追記する。

> **状態：設計（未着手）**。2026-10-17 作成。lessee プラグインを作るまで `plugins/finance/` に置く。会計の処理・開示の項目は、適用する基準書の最新版と、経理担当者・公認会計士の確認を前提とする。

## 進捗

| STEP | 内容 | 状態 |
|------|------|------|
| E0 | 基盤（2 章） | 設計（未着手） |
| E1 | 契約の台帳（3 章） | 設計（未着手） |
| E2 | 分類と当初の測定（4 章） | 設計（未着手） |
| E3 | 支払（5 章） | 設計（未着手） |
| E4 | 月次の決算（6 章） | 設計（未着手） |
| E5 | 帳票（7 章） | 設計（未着手） |
| E6 | 見直し（8 章） | 設計（未着手） |
| E7 | 物件の管理（9 章） | 設計（未着手） |
| E8 | 期限の管理（9 章） | 設計（未着手） |
| E9 | 連結（10 章） | 設計（未着手） |
| L22（finance） | 転貸リースの橋渡し（11 章、`REQUIREMENTS_LEASE.md` L22） | 設計（未着手） |

---

## 1. 構成

### 1.1 プラグインとパッケージ

| 項目 | 内容 |
|------|------|
| プラグイン | `plugins/lessee`（コンポーネント名 `lessee`、Web アプリ `/lessee`、基本権限 `OFBTOOLS,LESSEE`） |
| パッケージ | `org.apache.ofbiz.lessee.contract`（台帳）、`org.apache.ofbiz.lessee.measure`（分類・測定・計画）、`org.apache.ofbiz.lessee.standard`（会計基準の部品）、`org.apache.ofbiz.lessee.payment`（支払）、`org.apache.ofbiz.lessee.close`（決算）、`org.apache.ofbiz.lessee.report`（帳票）、`org.apache.ofbiz.lessee.asset`（物件・期限）、`org.apache.ofbiz.lessee.common`（計算・端数・営業日・仕訳の共通部品） |
| 依存 | OFBiz core だけ（finance に依存しない） |
| データ | `data/LesseeTypeData.xml`（seed）、`data/LesseeDemoData.xml`（demo）、`testdef/data/LesseeTestData.xml`（テスト） |

### 1.2 会計基準の部品

```java
public interface LesseeAccountingStandard {
    String getStandardId();                       // JGAAP_13 / JGAAP_34 / IFRS_16
    /** Classify a contract under this standard (and the company policy). */
    LesseeClassification classify(LesseeMeasureContext ctx);
    /** Initial measurement: liability, asset, and the interest / depreciation plan. */
    LesseeMeasurement measure(LesseeMeasureContext ctx, LesseeClassification cls);
    /** GL lines at the commencement (empty when not capitalized). */
    List<GlLine> commencementEntries(LesseeMeasurement m);
    /** GL lines of a lease payment invoice. */
    List<GlLine> paymentEntries(LesseeMeasurement m, LesseePaymentRow row);
    /** GL lines of a month-end close (depreciation, interest, reclassification). */
    List<GlLine> closeEntries(LesseeMeasurement m, YearMonth month);
    /** Remeasurement for a modification / reassessment (E6). */
    LesseeMeasurement remeasure(LesseeMeasurement current, LesseeChange change);
}
```

- 実装：`Jgaap13Standard`（現行）、`Jgaap34Standard`（新基準）、`Ifrs16Standard`。`lessee.properties` の `lessee.standard.<基準>=<クラス>` で登録する。
- 新基準と IFRS 第 16 号は共通の部分が多いので、共通の基底クラス（`RightOfUseStandardBase`）に置き、違い（少額の判定、開示の区分 等）だけを各クラスで実装する。
- 計算（現在価値・利息の配分・償却）は DB を読まない純粋な計算クラス（`LesseeCalculator`）に置き、テストしやすくする。

---

## 2. STEP E0 — 基盤

### 2.1 エンティティ

| エンティティ | 主な項目 |
|--------------|----------|
| `LesseeAccountingPolicy` | `organizationPartyId`、`fromDate` / `thruDate`、`primaryStandardId`、`shortTermExemption`（Y/N）、`lowValueExemption`（Y/N）、`lowValueThreshold`（金額・通貨）、`jgaapSimplifiedMethodEnumId`（なし／利子込み法／定額法）、`jgaapMaterialityRatio`（重要性の判定の比率、例 10%）、`separateNonLeaseComponents`（Y/N）、`capitalizationUnitEnumId`（物件／契約）、`depreciationMethodEnumId`（定額法 既定）、`roundingScale`・`roundingMode` |
| `LesseePolicyStandard` | 会社 × 副の基準（計算だけ行う） |
| `LesseeIncrementalRate` | 追加借入利子率の表：会社 × 通貨 × 期間（月）× 適用開始日 → 年率 |
| seed | 基準 `LESSEE_STANDARD`（`JGAAP_13`・`JGAAP_34`・`IFRS_16`）、分類 `LESSEE_CLASS`（`LSE_FIN_TRANSFER`・`LSE_FIN_NONTRANSFER`・`LSE_OPERATING`・`LSE_ROU`・`LSE_SHORT_TERM`・`LSE_LOW_VALUE`）、状態、勘定科目の種類、権限 |

### 2.2 勘定科目の種類（seed）

| 種類 | 科目（例） |
|------|------------|
| `LSE_ASSET_TANGIBLE` / `LSE_ASSET_INTANGIBLE` | リース資産（有形・無形）（現行） |
| `LSE_ROU_BUILDING` / `LSE_ROU_EQUIPMENT` / `LSE_ROU_VEHICLE` / `LSE_ROU_OTHER` | 使用権資産（種類別。種類は物件の種類から決める対応表を設定で持つ） |
| `LSE_ACCUM_DEPR` | 減価償却累計額 |
| `LSE_LIAB_CURRENT` / `LSE_LIAB_NONCURRENT` | リース債務・リース負債（流動・固定） |
| `LSE_INTEREST_EXP` / `LSE_DEPR_EXP` | 支払利息、減価償却費 |
| `LSE_RENT_EXP` / `LSE_SHORT_TERM_EXP` / `LSE_LOW_VALUE_EXP` / `LSE_VARIABLE_EXP` | 支払リース料、短期リース費用、少額リース費用、変動リース料 |
| `LSE_PREPAID` / `LSE_ACCRUED_INTEREST` / `LSE_FX_GAINLOSS` / `LSE_REMEASURE_GAINLOSS` | 前払リース料、未払利息、為替差損益、見直しの損益 |

仮払消費税・未払金・現預金は OFBiz の既存の種類を使う。

---

## 3. STEP E1 — 契約の台帳

### 3.1 エンティティ

| エンティティ | 主な項目 |
|--------------|----------|
| `LesseeContract` | `lesseeContractId`、`organizationPartyId`、`lessorPartyId`、`lessorContractNumber`、`departmentPartyId`（管理部門）、`contractDate`・`commencementDate`・`endDate`、`nonCancellableMonths`、`currencyUomId`、`paymentTimingEnumId`（前払・後払）、`paymentIntervalMonths`、`lessorImplicitRate`（任意）、`residualGuarantee`、`purchaseOptionPrice`・`purchaseOptionDate`、`ownershipTransfer`（Y/N）、`specialPurposeAsset`（Y/N）、`initialDirectCost`、`incentiveReceived`、`restorationCostEstimate`、`statusId`、備考 |
| `LesseeContractAsset` | 物件：`assetSeqId`、`fixedAssetTypeId`、`assetName`、`quantity`、`lessorPurchasePrice`（任意）、`cashPurchasePrice`（見積の現金購入価額）、`usefulLifeMonths`、`fixedAssetId`（E7 で OFBiz の FixedAsset と紐付け）、`rouAssetClassEnumId`（使用権資産の種類） |
| `LesseePaymentSchedule` | 契約のリース料の予定（revision つき）：回・期日・リース料・リース以外の部分（保守・保険・税）・消費税・変動（指数連動）か |
| `LesseeContractOption` | オプション：種類（延長・解約・購入）、行使可能日・期間・金額、行使の可能性の判断（`reasonablyCertain` Y/N、判断日・理由。基準ごとに持てる） |
| `LesseeVariablePayment` | 変動リース料：指数（OFBiz の Uom や独自の指数表）、改定の間隔、基準の値。使用量連動は「費用のみ」として区別 |
| `LesseeContractStatus` | 状態の履歴 |

### 3.2 状態（`LESSEE_CTR_STATUS`）

`LSECTR_DRAFT`（登録中）→ `LSECTR_CONFIRMED`（測定済み）→ `LSECTR_ACTIVE`（開始）→ `LSECTR_EXPIRED`（満了）／`LSECTR_TERMINATED`（中途解約）／`LSECTR_PURCHASED`（購入）→ `LSECTR_CLOSED`（完了）。`LSECTR_DRAFT` / `LSECTR_CONFIRMED` → `LSECTR_CANCELLED`。状態は専用のサービスだけで変える。

### 3.3 取込み

- `importLesseeContracts`（CSV）：契約・物件・リース料の予定。行ごとの検証結果を返し、エラーの行は登録しない。
- `importLesseeMigration`：移行日時点の残高（リース債務の残高・償却累計・前払）を入れ、移行日を測定の起点にする（新基準の適用時の経過措置を選べる：遡及・修正遡及。要確認）。

---

## 4. STEP E2 — 分類と当初の測定

### 4.1 分類

| 基準 | 判定 |
|------|------|
| 現行（第 13 号） | ①短期（1 年以内）・少額（会社の基準額以下、または 300 万円以下の契約 等の判定を設定で）→ 賃貸借の処理を選べる ②ファイナンスの判定：解約不能かつ（現在価値基準：リース料の現在価値 ≥ 見積の現金購入価額の 90%、または 経済的耐用年数基準：リース期間 ≥ 経済的耐用年数の 75%）③所有権移転の判定：所有権移転条項・割安購入選択権・特別仕様 ④重要性が乏しい場合の簡便法（利子込み法・定額法） |
| 新基準（第 34 号）・IFRS 第 16 号 | 短期リース・少額リースの例外（会社の方針で選択）、それ以外は使用権資産・リース負債。リース期間はオプションの判断を含める |

判定の結果と根拠（使った数値）は `LesseeClassificationResult` に保存する。

### 4.2 測定

| 項目 | 内容 |
|------|------|
| 割引率 | 貸手の計算利子率が分かればそれ、分からなければ `LesseeIncrementalRate`（会社 × 通貨 × 期間）。現行基準の所有権移転外は、貸手の計算利子率が分からないとき追加借入利子率 |
| リース負債 | 固定のリース料（リース以外の部分は方針で区分）＋指数連動の変動リース料（開始日の指数で）＋残価保証の支払見込み＋行使が確実な購入オプション＋解約の違約金（解約が確実なら）の現在価値 |
| 資産 | 新基準・IFRS：リース負債＋開始日以前の支払−インセンティブ＋当初直接費用＋原状回復費用。現行の所有権移転外：リース料の現在価値と見積の現金購入価額の低い方（貸手の購入価額が分かる場合はそれ） |
| 利息の配分 | 利息法（既定）。現行の簡便法は定額法・利子込み法（利息を区分しない） |
| 償却 | 所有権移転・購入が確実：経済的耐用年数。それ以外：リース期間。残存価額 0（現行の所有権移転外で残価保証があるときは残価保証額） |

`LesseeMeasurement`（契約 × 基準 × revision）：分類、割引率、リース期間、当初の負債・資産、計画の明細（`LesseeLiabilityPlan`：回ごとの元本・利息・残高、`LesseeDepreciationPlan`：月ごとの償却・累計）。

### 4.3 開始

- `confirmLesseeContract`：検証（物件・リース料の予定・方針）→ 基準ごとの分類と測定 → `LSECTR_CONFIRMED`。
- `commenceLesseeContract`：開始日に `LSECTR_ACTIVE` にし、主たる基準の開始の仕訳（1 回だけ）。計上しない分類（オペレーティング・短期・少額）は仕訳なし。

---

## 5. STEP E3 — 支払

| サービス | 内容 |
|----------|------|
| `matchLesseeInvoice` | 貸手の仕入請求書（OFBiz）を、契約・回に紐付ける（`LesseePaymentMatch`）。金額・期日の差を記録し、差があれば理由を入れて承認する |
| `createLesseeInvoiceFromSchedule` | 予定から仕入請求書を作る（請求書が届かない契約） |
| 仕訳 | 請求書の明細の勘定を、分類に応じて決める：計上する分類はリース負債（元本）と支払利息（その回の利息で、月次で未払利息に計上済みの分は未払利息を消す）、計上しない分類は支払リース料／短期／少額／変動の費用。リース以外の部分は費用（保守料等）。消費税は仮払消費税 |
| 支払 | OFBiz の支払・消込（`VENDOR_PAYMENT`）。lessee は支払の予定表と未払の一覧を出す |

---

## 6. STEP E4 — 月次の決算

| 処理 | 内容 |
|------|------|
| 減価償却 | 償却の計画の月の額（`LesseeDepreciationRecord`、契約 × 物件 × 基準 × 月、冪等） |
| 利息 | 利息法の月次の配分。支払日と月末がずれる分は未払利息（`LesseeInterestRecord`） |
| 流動・固定の振替 | 決算日の翌日から 12 か月以内に支払う元本を流動に。前月の振替を戻して当月を計上する（洗替え） |
| 外貨の換算 | 外貨建てのリース負債を決算日のレートで換算し、差額を為替差損益 |
| 配賦 | 費用（償却・利息・支払リース料）を部門・原価センターへ（契約・物件の管理部門、按分の比率） |
| 確定 | `closeLesseeMonth`（会社 × 月）：上の処理をすべて行い、帳票のスナップショット（7 章）を作り、確定（`LesseeMonthClose`）。確定後のその月の計上は変えない |

副の基準は、計上（仕訳）を行わず、記録（`LesseeDepreciationRecord`・`LesseeInterestRecord` の基準別の行）だけを作る。

---

## 7. STEP E5 — 帳票

### 7.1 スナップショット

`LesseeReportSnapshot`（会社 × 基準 × 基準日 × 帳票の種類）と明細（`LesseeReportLine`：区分・項目・金額）。月次の確定で作り、変えない。

### 7.2 帳票の一覧

| 帳票 | 区分・項目 |
|------|------------|
| 資産の増減表 | 種類別（現行：有形・無形のリース資産、新基準・IFRS：使用権資産の種類）× 期首・増加・減少・償却・見直し・換算・期末、取得価額・償却累計・帳簿価額 |
| 負債の明細 | 流動・固定 × 期首・増加・支払・利息・見直し・換算・期末 |
| 返済予定額 | 1 年以内・1〜2 年・2〜3 年・3〜4 年・4〜5 年・5 年超（現行の附属明細表の区分）。新基準・IFRS は満期分析（割引前）を同じ区分と、会社が選ぶ区分で |
| 未経過リース料（現行のオペレーティング） | 1 年内・1 年超（解約不能のもの） |
| 費用の内訳（新基準・IFRS） | 使用権資産の減価償却費（種類別）、リース負債の利息費用、短期リース費用、少額リース費用、変動リース料、転貸の収益（L22 の後） |
| キャッシュフロー | リースに係るキャッシュアウトの合計（元本・利息・短期・少額・変動） |
| 契約の一覧・明細 | 基準日の契約別：分類・割引率・リース期間・残高・オプションの判断 |
| 基準の比較・前期の比較 | 2 つのスナップショットの差 |

- 出力：画面、PDF（jasperreports）、CSV。CSV は区分・項目のコード付きで、開示資料・連結パッケージへの取込みに使う。
- 開示の区分・名称は、基準ごとの対応表（seed、`LesseeReportItem`）で持ち、基準書の改正に合わせてデータで直せるようにする。

---

## 8. STEP E6 — 見直し

| 見直し | 処理（新基準・IFRS の例。現行は基準に合わせて実装） |
|--------|------|
| 条件変更（範囲の縮小） | 資産・負債を縮小の割合で減らし、差を損益。残りを新しい割引率で測り直す |
| 条件変更（範囲の拡大・期間・金額） | 別のリースとして扱うか、負債を新しい割引率で測り直し、差を資産で調整 |
| オプションの判断の変更 | 新しいリース期間・新しい割引率で負債を測り直し、差を資産で調整 |
| 指数の改定 | 元の割引率で負債を測り直し、差を資産で調整 |
| 中途解約 | 資産・負債を消し、違約金との差を損益 |
| 購入オプションの行使 | 使用権資産（リース資産）を自社の固定資産に振り替え |
| 再リース（現行） | 再リース料の扱い（費用処理 等。要確認） |
| 減損 | 減損損失の記録（使用権資産の帳簿価額を減らし、以後の償却を見直す） |

すべて、計画の新しい revision と、見直しの記録（`LesseeRemeasurement`：前後の負債・資産・割引率・損益、仕訳）を残す。過去の確定した月は変えない。

---

## 9. STEP E7・E8 — 物件の管理・期限の管理

- 物件：OFBiz の `FixedAsset`（所有者は貸手、`partyId` に貸手。自社の管理用の項目は `LesseeContractAsset` に持つ）、設置場所（`Facility`）、管理部門、担当者、保守・保険の契約（`LesseeAssetService`：業者・期間・金額）、棚卸し（`LesseeAssetInventory`：予定・結果・差異）、移設の履歴。
- 期限：満了・解約予告・オプションの判断・返還の期限を `LesseeDeadline` で持ち、期限の一定前（設定、例：満了の 3 か月前）に作業（WorkEffort）のタスクを作る（契約 × 種類 × 期限ごとに 1 件、冪等）。タスクは業務のサービス（延長の登録・返還の記録）の結果で完了にする。期限のバッチはジョブスケジューラで実行する。

---

## 10. STEP E9 — 連結

| 段階 | 内容 |
|------|------|
| 1. 合算 | グループの会社（OFBiz の `PartyRelationship` の親子、または連結の範囲の設定 `LesseeConsolidationScope`）の帳票のスナップショットを、同じ基準・基準日で合算する |
| 2. 換算 | 外貨建ての会社の帳票を、連結の通貨に換算する（残高は期末レート、損益は期中平均レート、差額は換算差額） |
| 3. 消去 | グループ内のリース（借手の会社と貸手の会社がどちらもグループ）の資産・負債・費用・収益を消去する。貸手側の情報は手入力または CSV（finance を使っている場合の自動照合は将来の検討事項） |

---

## 11. STEP L22（finance）— 転貸リースの橋渡し

### 11.1 置き場所

- lessee と finance のどちらにも依存する小さなプラグイン **`leasesublease`**（仮称）に置く。lessee だけ・finance だけの環境では入れない。
- finance の要件の「貸手側のみ」の範囲は変えない（借手側の会計は lessee が持つ）。

### 11.2 データと処理（予定）

| 種類 | 内容 |
|------|------|
| `SubleaseLink` | 元のリース（`lesseeContractId`・物件）と転貸（finance の `leaseAgreementId`・物件）の紐付け、転貸の割合（物件の一部・期間の一部）、転貸の開始・終了 |
| 分類（転貸の貸手として） | 新基準・IFRS：元のリースの使用権資産を基準に、転貸をファイナンス／オペレーティングに分類する。ファイナンスなら使用権資産の転貸部分を消してリース投資資産（finance 側）にし、差を損益。現行基準：元のリースと転貸がどちらもファイナンスで一定の条件を満たすとき、リース投資資産とリース債務を利息相当額控除前で計上し、差額を手数料収入とする方法を選べる |
| 資金の表 | 元のリースの支払予定（lessee）と転貸の回収予定（finance）を並べた表と差 |
| 会計 | 転貸の請求・入金・延滞・督促は finance、元のリースの支払・決算は lessee。橋渡しは分類の判定と、両者の仕訳を合わせる調整の仕訳（使用権資産の消去 等）だけを作る |

### 11.3 要確認

- 現行基準の転貸の特例（利息相当額控除前の計上）の条件と仕訳、新基準・IFRS の中間の貸手の会計は、会計士の確認が必要。
- finance の契約（転貸）の取得価額・収益計上の方法を、元のリースの使用権資産に合わせる必要がある（finance の商品・見積の設定で扱えるかを実装時に確かめる）。

---

## 12. テストの方針

| 種類 | 内容 |
|------|------|
| 計算の単体テスト | `LesseeCalculator`：現在価値・利息法の配分・償却（手計算の例、前払・後払、不均等な支払、残価保証）、端数処理 |
| 分類のテスト | 現行の判定（90%・75%・所有権移転の条件・短期・少額・簡便法）、新基準・IFRS の例外 |
| 仕訳のテスト | 開始・支払・決算（償却・利息・振替・換算）の貸借一致と、基準ごとの科目 |
| 決算のテスト | 二重に計上しない（再実行で増えない）、確定後の変更の禁止、副の基準は仕訳を作らない |
| 帳票のテスト | 増減表の期首＋増減＝期末、返済予定額の合計＝割引前の残リース料、負債の流動＋固定＝負債の合計、基準の比較 |
| 見直しのテスト | 各見直しの前後の値と損益（E6） |

---

## 13. 要確認・制約

- **基準書の確認**：新しいリース会計基準（企業会計基準第 34 号）の適用時期・経過措置・開示の項目、IFRS 第 16 号の最新の改正を、実装の前に基準書で確かめる。本設計の数値（90%・75%・少額の基準額 等）は現行の一般的な例で、方針の設定で変えられるようにする。
- **会計士の確認**：分類・測定・見直し・転貸・移行の処理と仕訳は、会計士の確認を前提とする。
- **税務**：会計と税務の差（新基準の使用権資産の償却と税務上の賃借料 等）の調整表は、税理士の確認を前提とする。消費税の仕入税額控除の時期（一括・分割）も同様。
- **資産除去債務**：原状回復の費用を使用権資産に含める場合の資産除去債務の計上は、別の会計処理として要確認（初期は見積額の入力だけ）。
- **物件の所有者**：OFBiz の `FixedAsset` は貸手を所有者として登録する。自社所有の固定資産と区別して帳票を作る。
- **finance との重複**：利息法の計算・端数処理などは finance と重複して持つ。重複が大きくなったら共通のプラグインへの切り出しを検討する（`REQUIREMENTS_LESSEE.md` 5 章）。
