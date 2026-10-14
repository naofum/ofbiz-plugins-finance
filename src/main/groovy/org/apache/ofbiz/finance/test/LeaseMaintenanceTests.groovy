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
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.lease.LeaseAccountingServices
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L11: maintenance lease (メンテナンスリース). The maintenance fee is an L9
 * component billed as separate revenue on the maintenance revenue account
 * (403900); maintenance plans generate scheduled maintenances (record + core
 * FixedAssetMaint + WorkEffort task, idempotent); performed maintenance books the
 * provider's cost with a core purchase invoice (Dr 503900 / Cr accounts payable).
 *
 * Uses the USD lease product TEST_LEASEPROD_USD, a new lessee per test and lease
 * starts in the past so the installments are due.
 */
class LeaseMaintenanceTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')
    private static final BigDecimal FEE = new BigDecimal('500.00')
    private static final String PROVIDER = 'TEST_LS_SUPPLIER1'

    LeaseMaintenanceTests(String name) {
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

    private Map<String, BigDecimal> entrySums(String acctgTransId) {
        Map<String, BigDecimal> sums = [:]
        from('AcctgTransEntry').where('acctgTransId', acctgTransId).queryList().each { GenericValue e ->
            String key = e.glAccountId + ':' + e.debitCreditFlag
            sums[key] = (sums[key] ?: BigDecimal.ZERO).add(e.getBigDecimal('amount'))
        }
        return sums
    }

    private static BigDecimal side(Map<String, BigDecimal> sums, String flag) {
        return sums.findAll { it.key.endsWith(':' + flag) }.values().inject(BigDecimal.ZERO) { a, b -> a.add(b) }
    }

    private String newApplication(GenericValue userLogin, long termMonths) {
        String lessee = 'TEST_LSMNT_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Maintenance lease test lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: 'TEST_LEASEPROD_USD', applicantPartyId: lessee,
                requestedTermMonths: termMonths, userLogin: userLogin])
        ok(app)
        ok(dispatcher.runSync('createLeaseApplicationAsset', [leaseApplicationId: app.leaseApplicationId, fixedAssetTypeId: 'EQUIPMENT',
                assetName: 'Copier', quantity: BigDecimal.ONE, estimatedAcquisitionCost: COST,
                supplierPartyId: PROVIDER, userLogin: userLogin]))
        return app.leaseApplicationId
    }

    /** Maintenance lease (taxable maintenance fee, separate) accepted on {@code start}. */
    private String activeLease(GenericValue userLogin, LocalDate start, long termMonths) {
        String appId = newApplication(userLogin, termMonths)
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_SUBMITTED', userLogin: userLogin]))
        Map uw = dispatcher.runSync('createLeaseUnderwriting', [leaseApplicationId: appId, creditScore: 720L,
                approvedPrincipalAmount: COST, userLogin: userLogin])
        ok(uw)
        ok(dispatcher.runSync('setLoanUnderwritingStatus', [loanUnderwritingId: uw.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_APPROVED', userLogin: userLogin]))
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, termMonths: termMonths,
                leaseStartDate: ts(start), components: [[componentTypeEnumId: 'LSCMP_MAINTENANCE', monthlyAmount: FEE,
                        taxClassEnumId: 'LSTAX_TAXABLE', revenueTreatmentEnumId: 'LSCMP_REV_SEPARATE']], userLogin: userLogin])
        ok(quote)
        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote.leaseQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quote.leaseQuoteId,
                agreementDate: ts(start.minusDays(10)), userLogin: userLogin])
        ok(agreement)
        ok(dispatcher.runSync('recordLeaseAssetPurchase', [leaseAgreementId: agreement.leaseAgreementId, assetSeqId: '00001',
                purchaseDate: ts(start.minusDays(5)), userLogin: userLogin]))
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreement.leaseAgreementId, acceptanceDate: ts(start),
                userLogin: userLogin]))
        return agreement.leaseAgreementId
    }

    private GenericValue task(String workEffortId) {
        return from('WorkEffort').where('workEffortId', workEffortId).queryOne()
    }

    private GenericValue fam(GenericValue rec) {
        return from('FixedAssetMaint').where('fixedAssetId', rec.fixedAssetId, 'maintHistSeqId', rec.maintHistSeqId).queryOne()
    }

    // The maintenance fee is separate revenue on 403900 (not 403800, not included in the lease sales).
    void testMaintenanceFeeBilledAsSeparateRevenue() {
        GenericValue userLogin = admin()
        // "Included" is rejected for the maintenance fee.
        String appId = newApplication(userLogin, 60L)
        Map bad = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, termMonths: 60L,
                components: [[componentTypeEnumId: 'LSCMP_MAINTENANCE', monthlyAmount: FEE,
                        revenueTreatmentEnumId: 'LSCMP_REV_INCLUDED']], userLogin: userLogin])
        assert ServiceUtil.isError(bad)

        String agreementId = activeLease(userLogin, LocalDate.now().minusMonths(1), 60L)
        long revision = LeaseAccountingServices.currentScheduleRevision(delegator, agreementId)
        GenericValue row1 = from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', revision, 'installmentNumber', 1L).queryOne()
        GenericValue comp1 = from('LeaseAgrPaySchedComp').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', revision, 'installmentNumber', 1L).queryFirst()
        Map bill = dispatcher.runSync('createLeaseInstallmentInvoice', [leaseAgreementId: agreementId,
                installmentNumber: 1L, userLogin: userLogin])
        ok(bill)
        GenericValue trans = from('AcctgTrans').where('invoiceId', bill.invoiceId).queryFirst()
        Map<String, BigDecimal> sums = entrySums(trans.acctgTransId)
        assert side(sums, 'D').compareTo(side(sums, 'C')) == 0
        assert sums['403900:C'].compareTo(FEE) == 0                             // maintenance revenue
        assert sums['403800:C'] == null                                         // not the generic component revenue
        assert sums['128500:C'].compareTo(row1.leaseAmount) == 0                // lease part only
        assert sums['224500:C'].compareTo(row1.taxAmount.add(comp1.taxAmount)) == 0
        assert sums['403500:C'] == null                                         // no method-1 sales at billing

        // The contracted fee (60 x 500) is in the summary; billed revenue = one installment.
        Map summary = dispatcher.runSync('getLeaseMaintenanceSummary', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(summary)
        assert summary.contractedMaintenanceAmount.compareTo(FEE.multiply(new BigDecimal('60'))) == 0
        assert summary.billedMaintenanceRevenue.compareTo(FEE) == 0
    }

    // Plan -> batch: records, core FixedAssetMaint and tasks per due date within the horizon; idempotent.
    void testMaintenancePlanSchedule() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(1)
        String agreementId = activeLease(userLogin, start, 60L)
        // Invalid plans rejected.
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseMaintPlan', [leaseAgreementId: agreementId, assetSeqId: '00001',
                productMaintTypeId: 'LEASE_INSPECTION', intervalMonths: 0L, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseMaintPlan', [leaseAgreementId: agreementId, assetSeqId: '00001',
                productMaintTypeId: 'NO_SUCH_TYPE', intervalMonths: 3L, userLogin: userLogin]))
        // Inspection every 3 months from start + 3 months.
        Map plan = dispatcher.runSync('createLeaseMaintPlan', [leaseAgreementId: agreementId, assetSeqId: '00001',
                productMaintTypeId: 'LEASE_INSPECTION', intervalMonths: 3L, firstDueDate: ts(start.plusMonths(3)),
                providerPartyId: PROVIDER, estimatedCost: new BigDecimal('300.00'), userLogin: userLogin])
        ok(plan)

        // Horizon = today + 5 months: due dates start+3 (today+2) and start+6 (today+5).
        Map run = dispatcher.runSync('runLeaseMaintScheduleBatch', [leaseAgreementId: agreementId, monthsAhead: 5L,
                userLogin: userLogin])
        ok(run)
        assert run.createdCount == 2L
        List<GenericValue> recs = from('LeaseMaintRecord').where('leaseAgreementId', agreementId).orderBy('scheduledDate').queryList()
        assert recs.size() == 2
        assert recs[0].scheduledDate == ts(start.plusMonths(3))
        assert recs[1].scheduledDate == ts(start.plusMonths(6))
        recs.each { GenericValue r ->
            assert r.statusId == 'LSMNT_SCHEDULED'
            assert r.providerPartyId == PROVIDER
            GenericValue f = fam(r)
            assert f != null && f.statusId == 'FAM_SCHEDULED' && f.productMaintTypeId == 'LEASE_INSPECTION'
            assert f.scheduleWorkEffortId == r.workEffortId
            assert task(r.workEffortId).currentStatusId == 'CAL_NEEDS_ACTION'
            assert from('WorkEffortFixedAssetAssign').where('workEffortId', r.workEffortId, 'fixedAssetId', r.fixedAssetId).queryCount() == 1
        }
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        assert from('FinanceContractWorkEffort').where('financeContractId', agreement.financeContractId,
                'financeWorkTypeEnumId', 'FINWE_MAINTENANCE').queryCount() == 2

        // Idempotent; a longer horizon adds only the new due date.
        Map again = dispatcher.runSync('runLeaseMaintScheduleBatch', [leaseAgreementId: agreementId, monthsAhead: 5L, userLogin: userLogin])
        ok(again)
        assert again.createdCount == 0L
        Map longer = dispatcher.runSync('runLeaseMaintScheduleBatch', [leaseAgreementId: agreementId, monthsAhead: 8L, userLogin: userLogin])
        ok(longer)
        assert longer.createdCount == 1L

        // Ending the plan cancels the scheduled maintenances on or after the end date (record, FAM, task).
        Map end = dispatcher.runSync('endLeaseMaintPlan', [leaseAgreementId: agreementId, maintPlanSeqId: plan.maintPlanSeqId,
                thruDate: ts(start.plusMonths(6)), userLogin: userLogin])
        ok(end)
        assert end.cancelledCount == 2L
        recs = from('LeaseMaintRecord').where('leaseAgreementId', agreementId).orderBy('scheduledDate').queryList()
        assert recs[0].statusId == 'LSMNT_SCHEDULED'
        assert recs[1].statusId == 'LSMNT_CANCELLED' && recs[2].statusId == 'LSMNT_CANCELLED'
        assert fam(recs[1]).statusId == 'FAM_CANCELLED'
        assert task(recs[1].workEffortId).currentStatusId == 'CAL_CANCELLED'
        Map afterEnd = dispatcher.runSync('runLeaseMaintScheduleBatch', [leaseAgreementId: agreementId, monthsAhead: 12L, userLogin: userLogin])
        ok(afterEnd)
        assert afterEnd.createdCount == 0L
    }

    // Result: provider cost through a purchase invoice (Dr 503900 / Cr AP); record, FAM and task completed; summary margin.
    void testMaintenanceCompletionBooksCost() {
        GenericValue userLogin = admin()
        String agreementId = activeLease(userLogin, LocalDate.now().minusMonths(1), 60L)
        ok(dispatcher.runSync('createLeaseInstallmentInvoice', [leaseAgreementId: agreementId, installmentNumber: 1L, userLogin: userLogin]))

        // Unscheduled repair (default type LEASE_REPAIR).
        Map repair = dispatcher.runSync('createLeaseMaintenance', [leaseAgreementId: agreementId, assetSeqId: '00001',
                providerPartyId: PROVIDER, comments: 'Paper jam', userLogin: userLogin])
        ok(repair)
        GenericValue rec = from('LeaseMaintRecord').where('leaseMaintRecordId', repair.leaseMaintRecordId).queryOne()
        assert rec.productMaintTypeId == 'LEASE_REPAIR'
        assert rec.maintPlanSeqId == null

        // Negative cost rejected.
        assert ServiceUtil.isError(dispatcher.runSync('completeLeaseMaintenance', [leaseMaintRecordId: rec.leaseMaintRecordId,
                costAmount: new BigDecimal('-1'), userLogin: userLogin]))

        BigDecimal cost = new BigDecimal('320.00')
        Map done = dispatcher.runSync('completeLeaseMaintenance', [leaseMaintRecordId: rec.leaseMaintRecordId,
                costAmount: cost, supplierInvoiceRef: 'MNT-001', userLogin: userLogin])
        ok(done)
        assert done.invoiceId != null
        GenericValue invoice = from('Invoice').where('invoiceId', done.invoiceId).queryOne()
        assert invoice.invoiceTypeId == 'PURCHASE_INVOICE'
        assert invoice.partyIdFrom == PROVIDER
        assert invoice.statusId == 'INVOICE_READY'
        GenericValue trans = from('AcctgTrans').where('invoiceId', done.invoiceId).queryFirst()
        assert trans.isPosted == 'Y'
        Map<String, BigDecimal> sums = entrySums(trans.acctgTransId)
        assert sums['503900:D'].compareTo(cost) == 0                            // maintenance cost
        assert side(sums, 'C').compareTo(cost) == 0                             // accounts payable
        assert side(sums, 'D').compareTo(side(sums, 'C')) == 0

        rec.refresh()
        assert rec.statusId == 'LSMNT_COMPLETED'
        assert rec.costAmount.compareTo(cost) == 0
        assert rec.supplierInvoiceRef == 'MNT-001'
        assert fam(rec).statusId == 'FAM_COMPLETED'
        assert task(rec.workEffortId).currentStatusId == 'CAL_COMPLETED'
        // Completed maintenance cannot be completed or cancelled again.
        assert ServiceUtil.isError(dispatcher.runSync('completeLeaseMaintenance', [leaseMaintRecordId: rec.leaseMaintRecordId,
                costAmount: cost, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('cancelLeaseMaintenance', [leaseMaintRecordId: rec.leaseMaintRecordId,
                userLogin: userLogin]))

        // A no-cost maintenance (under warranty) books nothing; one with cost needs a provider.
        Map free = dispatcher.runSync('createLeaseMaintenance', [leaseAgreementId: agreementId, assetSeqId: '00001',
                productMaintTypeId: 'LEASE_INSPECTION', userLogin: userLogin])
        ok(free)
        assert ServiceUtil.isError(dispatcher.runSync('completeLeaseMaintenance', [leaseMaintRecordId: free.leaseMaintRecordId,
                costAmount: new BigDecimal('10'), userLogin: userLogin]))
        Map freeDone = dispatcher.runSync('completeLeaseMaintenance', [leaseMaintRecordId: free.leaseMaintRecordId, userLogin: userLogin])
        ok(freeDone)
        assert freeDone.invoiceId == null

        // Summary: billed revenue (one installment) - cost.
        Map summary = dispatcher.runSync('getLeaseMaintenanceSummary', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(summary)
        assert summary.billedMaintenanceRevenue.compareTo(FEE) == 0
        assert summary.maintenanceCost.compareTo(cost) == 0
        assert summary.maintenanceMargin.compareTo(FEE.subtract(cost)) == 0
        assert summary.completedCount == 2L
    }
}
