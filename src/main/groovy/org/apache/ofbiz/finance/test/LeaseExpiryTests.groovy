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
package org.apache.ofbiz.finance.test

import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDate
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.common.FinanceContractHelper
import org.apache.ofbiz.finance.lease.LeaseExpiryServices
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L7: lease expiry with tasks (core WorkEffort through the common work management):
 * expiry list and tasks, expiry notice, return / disposal (no transfer) with the residual
 * settlement, ownership transfer, completion rules, and a task on a loan contract.
 *
 * Each test creates its own USD lease product (12 months, rate factor 0.09, residual)
 * and a new lessee; the leases start 14 months ago so they have expired.
 */
class LeaseExpiryTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    LeaseExpiryTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private static Timestamp ts(LocalDate d) {
        return Timestamp.valueOf(d.atStartOfDay())
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String newParty() {
        String partyId = 'TEST_LSEXP_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: partyId, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: partyId, groupName: 'Lease expiry test lessee'])
        return partyId
    }

    private String product(GenericValue userLogin, String typeId, String residualRate) {
        Map product = dispatcher.runSync('createFinanceProduct', [productName: 'Test Lease Expiry USD',
                financialProductTypeId: typeId, currencyUomId: 'USD', minTermMonths: 12L, maxTermMonths: 72L,
                userLogin: userLogin])
        ok(product)
        ok(dispatcher.runSync('createFinancialProductLease', [productId: product.productId,
                defaultMonthlyRateFactor: new BigDecimal('0.090000'), residualValueRate: new BigDecimal(residualRate),
                userLogin: userLogin]))
        return product.productId
    }

    /** Accepted 12-month lease of the product started 14 months ago, every installment billed. */
    private String expiredLease(GenericValue userLogin, String productId, String lessee) {
        LocalDate start = LocalDate.now().minusMonths(14)
        Map app = dispatcher.runSync('createLeaseApplication', [productId: productId, applicantPartyId: lessee,
                requestedTermMonths: 12L, userLogin: userLogin])
        ok(app)
        String appId = app.leaseApplicationId
        ok(dispatcher.runSync('createLeaseApplicationAsset', [leaseApplicationId: appId, fixedAssetTypeId: 'EQUIPMENT',
                assetName: 'Machine', quantity: BigDecimal.ONE, estimatedAcquisitionCost: COST,
                supplierPartyId: 'TEST_LS_SUPPLIER1', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_SUBMITTED', userLogin: userLogin]))
        Map uw = dispatcher.runSync('createLeaseUnderwriting', [leaseApplicationId: appId, creditScore: 720L,
                approvedPrincipalAmount: COST, userLogin: userLogin])
        ok(uw)
        ok(dispatcher.runSync('setLoanUnderwritingStatus', [loanUnderwritingId: uw.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_APPROVED', userLogin: userLogin]))
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, termMonths: 12L,
                leaseStartDate: ts(start), userLogin: userLogin])
        ok(quote)
        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote.leaseQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quote.leaseQuoteId,
                agreementDate: ts(start.minusDays(10)), userLogin: userLogin])
        ok(agreement)
        String agreementId = agreement.leaseAgreementId
        ok(dispatcher.runSync('recordLeaseAssetPurchase', [leaseAgreementId: agreementId, assetSeqId: '00001',
                purchaseDate: ts(start.minusDays(5)), userLogin: userLogin]))
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId, acceptanceDate: ts(start),
                userLogin: userLogin]))
        ok(dispatcher.runSync('runLeaseMonthlyAccountingBatch', [leaseAgreementId: agreementId, userLogin: userLogin]))
        return agreementId
    }

    private GenericValue agreementOf(String agreementId) {
        return from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
    }

    private void payAll(String agreementId, GenericValue userLogin) {
        BigDecimal open = from('LeaseInstallmentInvoice').where('leaseAgreementId', agreementId).queryList()
                .inject(BigDecimal.ZERO) { BigDecimal s, GenericValue l -> s.add(l.billedAmount) }
        ok(dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, amount: open, userLogin: userLogin]))
    }

    private GenericValue task(String contractId, String workType, String trigger) {
        GenericValue link = from('FinanceContractWorkEffort').where('financeContractId', contractId,
                'financeWorkTypeEnumId', workType, 'triggerKey', trigger).queryFirst()
        return link == null ? null : from('WorkEffort').where('workEffortId', link.workEffortId).queryOne()
    }

    /** Sum of the entries of the account over the given transactions, debit positive. */
    private BigDecimal net(String glAccountId, List<String> transIds) {
        BigDecimal total = BigDecimal.ZERO
        transIds.findAll { it }.each { String id ->
            from('AcctgTransEntry').where('acctgTransId', id, 'glAccountId', glAccountId).queryList().each { GenericValue e ->
                total = e.debitCreditFlag == 'D' ? total.add(e.amount) : total.subtract(e.amount)
            }
        }
        return total
    }

    private List<String> leaseTransIds(String agreementId) {
        List<String> ids = [from('LeaseAcceptance').where('leaseAgreementId', agreementId).queryOne().startAcctgTransId]
        from('LeaseInstallmentInvoice').where('leaseAgreementId', agreementId).queryList().each { GenericValue l ->
            ids.addAll(from('AcctgTrans').where('invoiceId', l.invoiceId).queryList()*.acctgTransId)
        }
        ids.addAll(from('LeaseAssetSettlement').where('leaseAgreementId', agreementId).queryList()*.acctgTransId)
        return ids
    }

    // No transfer: list, tasks, notice, return before / after settlement, completion, disposal with gain.
    void testReturnDisposalAndCompletion() {
        GenericValue userLogin = admin()
        String lessee = newParty()
        String agreementId = expiredLease(userLogin, product(userLogin, 'LEASE_FIN_NONTRANS', '0.100000'), lessee)
        GenericValue agreement = agreementOf(agreementId)
        String contractId = agreement.financeContractId
        LocalDate end = agreement.leaseEndDate.toLocalDateTime().toLocalDate()
        BigDecimal residual = agreement.residualValue
        assert residual.compareTo(new BigDecimal('100000.00')) == 0

        // Not completed by the generic status service; no return before the lease end.
        assert ServiceUtil.isError(dispatcher.runSync('setLeaseAgreementStatus', [leaseAgreementId: agreementId,
                statusId: 'LEASEAGR_COMPLETED', userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('returnLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                returnDate: ts(end.minusDays(1)), userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('transferLeaseOwnership', [leaseAgreementId: agreementId, userLogin: userLogin]))

        // Expiry list and tasks (idempotent).
        assert LeaseExpiryServices.findExpiring(delegator, UtilDateTime.nowTimestamp(), 3)*.leaseAgreementId.contains(agreementId)
        Map batch = dispatcher.runSync('runLeaseExpiryBatch', [userLogin: userLogin, assigneePartyId: 'TEST_LS_SUPPLIER1'])
        ok(batch)
        GenericValue noticeTask = task(contractId, 'FINWE_EXPIRY_NOTICE', end.toString())
        GenericValue returnTask = task(contractId, 'FINWE_ASSET_RETURN', '00001')
        assert noticeTask.workEffortTypeId == 'TASK' && noticeTask.currentStatusId == 'CAL_NEEDS_ACTION'
        assert returnTask != null
        assert from('WorkEffortFixedAssetAssign').where('workEffortId', returnTask.workEffortId).queryCount() == 1
        assert from('WorkEffortPartyAssignment').where('workEffortId', returnTask.workEffortId,
                'partyId', 'TEST_LS_SUPPLIER1').queryCount() == 1
        long links = from('FinanceContractWorkEffort').where('financeContractId', contractId).queryCount()
        ok(dispatcher.runSync('runLeaseExpiryBatch', [userLogin: userLogin]))
        assert from('FinanceContractWorkEffort').where('financeContractId', contractId).queryCount() == links

        // Notice: completes its task; the agreement stays ACTIVE.
        ok(dispatcher.runSync('issueLeaseExpiryNotice', [leaseAgreementId: agreementId, userLogin: userLogin]))
        assert task(contractId, 'FINWE_EXPIRY_NOTICE', end.toString()).currentStatusId == 'CAL_COMPLETED'
        assert agreementOf(agreementId).statusId == 'LEASEAGR_ACTIVE'

        // Completing a task by hand changes no business status.
        ok(dispatcher.runSync('updateWorkEffort', [workEffortId: returnTask.workEffortId, currentStatusId: 'CAL_COMPLETED',
                userLogin: userLogin]))
        assert agreementOf(agreementId).statusId == 'LEASEAGR_ACTIVE'
        assert from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
                .assetStatusId == 'LSAST_LEASED'

        // Return with unpaid installments: recorded, but the agreement is not completed.
        Map returned = dispatcher.runSync('returnLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                returnDate: ts(end.plusDays(3)), userLogin: userLogin])
        ok(returned)
        assert returned.agreementCompleted == false
        assert from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
                .assetStatusId == 'LSAST_RETURNED'
        assert task(contractId, 'FINWE_ASSET_DISPOSAL', '00001').currentStatusId == 'CAL_NEEDS_ACTION'
        assert ServiceUtil.isError(dispatcher.runSync('completeLeaseAgreement', [leaseAgreementId: agreementId, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('returnLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                userLogin: userLogin]))                                            // already returned

        // Paid: the agreement is completed; the deferred profit is fully released.
        payAll(agreementId, userLogin)
        Map completed = dispatcher.runSync('completeLeaseAgreement', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(completed)
        assert completed.agreementCompleted == true
        agreement = agreementOf(agreementId)
        assert agreement.statusId == 'LEASEAGR_COMPLETED' && agreement.completedDate != null
        assert from('FinanceContract').where('financeContractId', contractId).queryOne().statusId == 'FINCTR_COMPLETED'
        BigDecimal released = from('LeaseProfitRecognition').where('leaseAgreementId', agreementId).queryList()
                .inject(BigDecimal.ZERO) { BigDecimal s, GenericValue r -> s.add(r.amount) }
        assert released.compareTo(agreement.totalInterestAmount) == 0

        // Disposal after completion: sale above the residual -> gain; asset and FixedAsset closed; task done.
        assert ServiceUtil.isError(dispatcher.runSync('disposeLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                disposalTypeEnumId: 'LEASE_DISP_SCRAP', proceedsAmount: new BigDecimal('10.00'), userLogin: userLogin]))
        BigDecimal proceeds = new BigDecimal('130000.00')
        Map disposed = dispatcher.runSync('disposeLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                disposalTypeEnumId: 'LEASE_DISP_SALE', proceedsAmount: proceeds, disposalPartyId: 'TEST_LS_SUPPLIER1',
                userLogin: userLogin])
        ok(disposed)
        assert disposed.gainLossAmount.compareTo(new BigDecimal('30000.00')) == 0
        List entries = from('AcctgTransEntry').where('acctgTransId', disposed.acctgTransId).queryList()
        assert entries.find { it.glAccountId == '128900' && it.debitCreditFlag == 'D' }.amount.compareTo(proceeds) == 0
        assert entries.find { it.glAccountId == '128500' && it.debitCreditFlag == 'C' }.amount.compareTo(residual) == 0
        assert entries.find { it.glAccountId == '403700' && it.debitCreditFlag == 'C' }.amount.compareTo(new BigDecimal('30000.00')) == 0
        GenericValue asset = from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        assert asset.assetStatusId == 'LSAST_DISPOSED'
        assert from('FixedAsset').where('fixedAssetId', asset.fixedAssetId).queryOne().actualEndOfLife != null
        assert task(contractId, 'FINWE_ASSET_DISPOSAL', '00001').currentStatusId == 'CAL_COMPLETED'
        assert ServiceUtil.isError(dispatcher.runSync('disposeLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                disposalTypeEnumId: 'LEASE_DISP_SCRAP', userLogin: userLogin]))   // only once

        // The lease investment nets to zero over start, billing and settlement.
        assert net('128500', leaseTransIds(agreementId)).signum() == 0
    }

    // Transfer: only after the final payment; ownership to the lessee; residual settled at the price.
    void testOwnershipTransfer() {
        GenericValue userLogin = admin()
        String lessee = newParty()
        String agreementId = expiredLease(userLogin, product(userLogin, 'LEASE_FIN_TRANSFER', '0.050000'), lessee)
        GenericValue agreement = agreementOf(agreementId)
        String contractId = agreement.financeContractId
        BigDecimal residual = agreement.residualValue
        assert residual.compareTo(new BigDecimal('50000.00')) == 0

        assert ServiceUtil.isError(dispatcher.runSync('returnLeaseAsset', [leaseAgreementId: agreementId, assetSeqId: '00001',
                userLogin: userLogin]))                                            // transfer leases are not returned
        assert ServiceUtil.isError(dispatcher.runSync('transferLeaseOwnership', [leaseAgreementId: agreementId,
                userLogin: userLogin]))                                            // unpaid
        ok(dispatcher.runSync('runLeaseExpiryBatch', [userLogin: userLogin]))
        assert task(contractId, 'FINWE_OWN_TRANSFER', 'TRANSFER') == null

        payAll(agreementId, userLogin)
        ok(dispatcher.runSync('runLeaseExpiryBatch', [userLogin: userLogin]))
        assert task(contractId, 'FINWE_OWN_TRANSFER', 'TRANSFER').currentStatusId == 'CAL_NEEDS_ACTION'

        Map transferred = dispatcher.runSync('transferLeaseOwnership', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(transferred)
        assert transferred.agreementCompleted == true
        assert agreementOf(agreementId).statusId == 'LEASEAGR_COMPLETED'
        assert task(contractId, 'FINWE_OWN_TRANSFER', 'TRANSFER').currentStatusId == 'CAL_COMPLETED'
        GenericValue asset = from('LeaseAgreementAsset').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        assert asset.assetStatusId == 'LSAST_TRANSFERRED'
        assert from('FixedAsset').where('fixedAssetId', asset.fixedAssetId).queryOne().partyId == lessee
        GenericValue settlement = from('LeaseAssetSettlement').where('leaseAgreementId', agreementId, 'assetSeqId', '00001').queryOne()
        assert settlement.proceedsAmount.compareTo(residual) == 0 && settlement.gainLossAmount.signum() == 0
        assert settlement.disposalPartyId == lessee
        // The lease receivable nets to zero; the transfer price is a receivable from the lessee.
        assert net('128600', leaseTransIds(agreementId)).signum() == 0
        assert net('128900', [settlement.acctgTransId]).compareTo(residual) == 0
        assert ServiceUtil.isError(dispatcher.runSync('transferLeaseOwnership', [leaseAgreementId: agreementId,
                userLogin: userLogin]))                                            // no longer active
    }

    // The common work management also serves loan contracts (idempotent per trigger key).
    void testTaskOnLoanContract() {
        GenericValue userLogin = admin()
        String contractId = FinanceContractHelper.createContract(delegator, 'LOAN', 'TEST_BORROWER1', 'TEST_LOANPROD1',
                'USD', new BigDecimal('1000.00'), UtilDateTime.nowTimestamp(), 'FINCTR_CANCELLED')
        Map first = dispatcher.runSync('createFinanceContractTask', [financeContractId: contractId,
                financeWorkTypeEnumId: 'FINWE_OTHER', triggerKey: 'CALL-1', workEffortName: 'Call the borrower',
                assigneePartyId: 'TEST_BORROWER1', userLogin: userLogin])
        ok(first)
        assert first.taskCreated == true
        Map again = dispatcher.runSync('createFinanceContractTask', [financeContractId: contractId,
                financeWorkTypeEnumId: 'FINWE_OTHER', triggerKey: 'CALL-1', workEffortName: 'Call the borrower',
                userLogin: userLogin])
        ok(again)
        assert again.taskCreated == false && again.workEffortId == first.workEffortId
        GenericValue we = from('WorkEffort').where('workEffortId', first.workEffortId).queryOne()
        assert we.workEffortTypeId == 'TASK' && we.currentStatusId == 'CAL_NEEDS_ACTION'
        assert from('WorkEffortPartyAssignment').where('workEffortId', first.workEffortId, 'partyId', 'TEST_BORROWER1').queryCount() == 1
        assert from('FinanceContract').where('financeContractId', contractId).queryOne().statusId == 'FINCTR_CANCELLED'
        assert ServiceUtil.isError(dispatcher.runSync('createFinanceContractTask', [financeContractId: 'NO_SUCH',
                workEffortName: 'x', userLogin: userLogin]))
    }
}
