/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// Screen data for ManageLeaseAgreement (STEP L2): agreement header, asset lines
// and the current revision of the confirmed lease payment schedule.

import org.apache.ofbiz.accounting.invoice.InvoiceWorker
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.common.FinanceWorkEffortHelper
import org.apache.ofbiz.finance.lease.LeaseAccountingServices
import org.apache.ofbiz.finance.lease.LeaseMaintenanceServices

GenericValue leaseAgreement = from('LeaseAgreement').where('leaseAgreementId', parameters.leaseAgreementId).queryOne()
context.leaseAgreement = leaseAgreement
if (leaseAgreement == null) {
    return
}
context.leaseAgreementId = leaseAgreement.leaseAgreementId
context.currencyUomId = leaseAgreement.currencyUomId
context.showFixedAsset = true
// Asset lines with their purchase (STEP L3).
context.assetLines = from('LeaseAgreementAsset').where('leaseAgreementId', leaseAgreement.leaseAgreementId)
        .orderBy('assetSeqId').queryList().collect { GenericValue a ->
    GenericValue purchase = from('LeaseAssetPurchase').where('leaseAgreementId', a.leaseAgreementId, 'assetSeqId', a.assetSeqId).queryOne()
    Map row = new LinkedHashMap(a.getAllFields())
    row.invoiceId = purchase?.invoiceId
    row.paymentId = purchase?.paymentId
    row.purchaseDate = purchase?.purchaseDate
    row.canPurchase = leaseAgreement.statusId == 'LEASEAGR_CONTRACTED' && purchase == null
    row.canPay = purchase != null && !purchase.paymentId
    return row
}
context.allPurchased = context.assetLines.every { it.invoiceId }
context.leaseAcceptance = from('LeaseAcceptance').where('leaseAgreementId', leaseAgreement.leaseAgreementId).queryOne()
context.canAccept = leaseAgreement.statusId == 'LEASEAGR_CONTRACTED' && context.allPurchased
GenericValue latest = from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', leaseAgreement.leaseAgreementId)
        .orderBy('-scheduleRevisionNumber').queryFirst()
context.scheduleRevisionNumber = latest?.scheduleRevisionNumber
context.scheduleRows = latest == null ? [] : from('LeaseAgreementPaymentSchedule')
        .where('leaseAgreementId', leaseAgreement.leaseAgreementId, 'scheduleRevisionNumber', latest.scheduleRevisionNumber)
        .orderBy('installmentNumber').queryList()
// STEP L9: lease payment components (definition list)
context.componentLines = from('LeaseAgreementComponent').where('leaseAgreementId', leaseAgreement.leaseAgreementId)
        .orderBy('componentSeqId').queryList()
context.canCancel = leaseAgreement.statusId == 'LEASEAGR_CONTRACTED' && !context.assetLines.any { it.invoiceId }

// STEP L4: billing, deferred profit release, payments and the collection status.
context.isActive = leaseAgreement.statusId == 'LEASEAGR_ACTIVE'
context.leaseInvoices = from('LeaseInstallmentInvoice').where('leaseAgreementId', leaseAgreement.leaseAgreementId)
        .orderBy('installmentNumber').queryList().collect { GenericValue link ->
    Map row = new LinkedHashMap(link.getAllFields())
    GenericValue invoice = from('Invoice').where('invoiceId', link.invoiceId).queryOne()
    row.invoiceStatusId = invoice?.statusId
    row.notApplied = invoice == null ? null : InvoiceWorker.getInvoiceNotApplied(invoice)
    return row
}
context.profitRecognitions = from('LeaseProfitRecognition').where('leaseAgreementId', leaseAgreement.leaseAgreementId)
        .orderBy('fromDate').queryList()
context.paymentReceipts = from('LeasePaymentReceipt').where('leaseAgreementId', leaseAgreement.leaseAgreementId)
        .orderBy('receiptDate').queryList()
context.leaseBalance = LeaseAccountingServices.computeBalance(delegator, leaseAgreement)

// STEP L6: delinquency records of the common layer (keyed by financeContractId).
String financeContractId = leaseAgreement.financeContractId
context.leaseDelinquencies = from('LoanDelinquency').where('financeContractId', financeContractId)
        .orderBy('-asOfDate').queryList()
context.leaseLateFeeAccruals = from('LoanLateFeeAccrual').where('financeContractId', financeContractId)
        .orderBy('fromDate').queryList()
context.leaseDunningNotices = from('LoanDunningNotice').where('financeContractId', financeContractId)
        .orderBy('noticeDate').queryList()
context.leaseDelinquencyActions = from('LoanDelinquencyAction').where('financeContractId', financeContractId)
        .orderBy('actionDate').queryList()

// STEP L7: expiry (notice, settlements per asset) and the contract tasks (core WorkEffort).
context.isTransferLease = leaseAgreement.leaseClassEnumId == 'LEASE_CLS_FIN_TRANS'
context.expiryNotice = from('LeaseExpiryNotice').where('leaseAgreementId', leaseAgreement.leaseAgreementId).queryOne()
context.settlements = from('LeaseAssetSettlement').where('leaseAgreementId', leaseAgreement.leaseAgreementId)
        .orderBy('assetSeqId').queryList()
context.assetLines.each { Map row ->
    row.canReturn = context.isActive && !context.isTransferLease && row.assetStatusId == 'LSAST_LEASED'
    row.canDispose = row.assetStatusId == 'LSAST_RETURNED'
}
context.returnedAssets = context.assetLines.findAll { it.canDispose }
context.canTransfer = context.isActive && context.isTransferLease
context.canIssueNotice = context.isActive && context.expiryNotice == null
context.contractTasks = FinanceWorkEffortHelper.listTasks(delegator, financeContractId).collect { GenericValue link ->
    GenericValue task = from('WorkEffort').where('workEffortId', link.workEffortId).queryOne()
    Map row = new LinkedHashMap(link.getAllFields())
    row.workEffortName = task?.workEffortName
    row.currentStatusId = task?.currentStatusId
    row.estimatedCompletionDate = task?.estimatedCompletionDate
    row.actualCompletionDate = task?.actualCompletionDate
    return row
}
// STEP L11: maintenance plans, schedule / results and the revenue / cost summary.
context.canAddMaintPlan = leaseAgreement.statusId in ['LEASEAGR_CONTRACTED', 'LEASEAGR_ACTIVE']
context.maintPlans = from('LeaseMaintPlan').where('leaseAgreementId', leaseAgreement.leaseAgreementId)
        .orderBy('maintPlanSeqId').queryList().collect { GenericValue p ->
    Map row = new LinkedHashMap(p.getAllFields())
    row.canEnd = p.thruDate == null && context.canAddMaintPlan
    return row
}
context.maintRecords = from('LeaseMaintRecord').where('leaseAgreementId', leaseAgreement.leaseAgreementId)
        .orderBy('scheduledDate', 'leaseMaintRecordId').queryList().collect { GenericValue r ->
    Map row = new LinkedHashMap(r.getAllFields())
    row.canComplete = r.statusId == 'LSMNT_SCHEDULED'
    row.scheduledDateText = r.scheduledDate?.toString()?.substring(0, 10)
    return row
}
context.scheduledMaintRecords = context.maintRecords.findAll { it.canComplete }
context.leasedAssets = context.assetLines.findAll { it.assetStatusId == 'LSAST_LEASED' }
context.maintMonthsAhead = LeaseMaintenanceServices.scheduleMonthsOf(delegator)
Map summary = dispatcher.runSync('getLeaseMaintenanceSummary', [leaseAgreementId: leaseAgreement.leaseAgreementId,
        userLogin: userLogin])
context.maintSummary = summary