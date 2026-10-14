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
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.LocalDate
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.lease.LeaseAccountingServices
import org.apache.ofbiz.finance.lease.LeaseComponentCalculator
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L9: lease payment components (維持管理費用相当額). Property tax equivalent,
 * insurance and maintenance paid in addition to the lease payment, with a 4-kind
 * consumption tax classification (課税 / 非課税 / 不課税 / 免税) and a revenue treatment
 * (separate 区分 / included 含める). The principal and the interest equivalent are
 * unchanged by components.
 *
 * Uses the USD lease product TEST_LEASEPROD_USD (base currency of Company), a new
 * lessee per test and lease starts in the past so the installments are due.
 */
class LeaseComponentTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    LeaseComponentTests(String name) {
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

    private List<GenericValue> schedule(String agreementId) {
        long revision = LeaseAccountingServices.currentScheduleRevision(delegator, agreementId)
        return from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', revision).orderBy('installmentNumber').queryList()
    }

    private Map balance(String agreementId, GenericValue userLogin) {
        Map result = dispatcher.runSync('getLeaseAgreementBalance', [leaseAgreementId: agreementId, userLogin: userLogin])
        ok(result)
        return result
    }

    /** New lessee with a contracted (not yet accepted) lease starting on {@code start}, with the given components. */
    private Map contractedLease(GenericValue userLogin, LocalDate start, long termMonths, List components) {
        String lessee = 'TEST_LSCMP_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Lease component test lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: 'TEST_LEASEPROD_USD', applicantPartyId: lessee,
                requestedTermMonths: termMonths, userLogin: userLogin])
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
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, termMonths: termMonths,
                leaseStartDate: ts(start), components: components, userLogin: userLogin])
        ok(quote)
        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote.leaseQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quote.leaseQuoteId,
                agreementDate: ts(start.minusDays(10)), userLogin: userLogin])
        ok(agreement)
        ok(dispatcher.runSync('recordLeaseAssetPurchase', [leaseAgreementId: agreement.leaseAgreementId, assetSeqId: '00001',
                purchaseDate: ts(start.minusDays(5)), userLogin: userLogin]))
        return [leaseQuoteId: quote.leaseQuoteId, leaseAgreementId: agreement.leaseAgreementId]
    }

    private String activeLease(GenericValue userLogin, LocalDate start, long termMonths, List components) {
        Map ids = contractedLease(userLogin, start, termMonths, components)
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: ids.leaseAgreementId, acceptanceDate: ts(start),
                userLogin: userLogin]))
        return ids.leaseAgreementId
    }

    // Pure calculation: level monthly amounts; consumption tax only for the taxable (課税) classification.
    void testComponentCalculator() {
        BigDecimal rate = new BigDecimal('0.10')
        // Taxable: amount x rate per installment, totals = amount x term.
        LeaseComponentCalculator.Component taxable = new LeaseComponentCalculator.Component('LSCMP_PROP_TAX',
                new BigDecimal('1000.00'), LeaseComponentCalculator.TAX_TAXABLE, LeaseComponentCalculator.REV_SEPARATE)
        LeaseComponentCalculator.Result rt = LeaseComponentCalculator.allocate(taxable, 12, rate, 2, RoundingMode.HALF_UP)
        assert rt.rows.size() == 12
        assert rt.rows[0].amount.compareTo(new BigDecimal('1000.00')) == 0
        assert rt.rows[0].taxAmount.compareTo(new BigDecimal('100.00')) == 0
        assert rt.totalAmount.compareTo(new BigDecimal('12000.00')) == 0
        assert rt.totalTaxAmount.compareTo(new BigDecimal('1200.00')) == 0

        // Exempt (非課税), non-taxable (不課税), zero-rated (免税): no consumption tax.
        [LeaseComponentCalculator.TAX_EXEMPT, LeaseComponentCalculator.TAX_NONTAX, LeaseComponentCalculator.TAX_ZERO].each { String cls ->
            LeaseComponentCalculator.Component c = new LeaseComponentCalculator.Component('LSCMP_INSURANCE',
                    new BigDecimal('500.00'), cls, LeaseComponentCalculator.REV_SEPARATE)
            LeaseComponentCalculator.Result r = LeaseComponentCalculator.allocate(c, 6, rate, 2, RoundingMode.HALF_UP)
            assert r.totalAmount.compareTo(new BigDecimal('3000.00')) == 0
            assert r.totalTaxAmount.signum() == 0
            assert r.rows.every { it.taxAmount.signum() == 0 }
        }

        // Rejected: negative amount, non-positive term.
        try {
            LeaseComponentCalculator.allocate(new LeaseComponentCalculator.Component('LSCMP_OTHER',
                    new BigDecimal('-1.00'), LeaseComponentCalculator.TAX_TAXABLE, LeaseComponentCalculator.REV_SEPARATE),
                    6, rate, 2, RoundingMode.HALF_UP)
            assert false : 'negative amount should be rejected'
        } catch (IllegalArgumentException ignored) {
        }
    }

    // Quote and agreement store the component definition and the per-installment amounts;
    // the lease payment (principal + interest) is unchanged by components. 4-kind tax classification.
    void testQuoteAndAgreementComponents() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(2)
        List components = [
                [componentTypeEnumId: 'LSCMP_PROP_TAX', description: 'Property tax', monthlyAmount: new BigDecimal('1000.00'),
                 taxClassEnumId: 'LSTAX_TAXABLE', revenueTreatmentEnumId: 'LSCMP_REV_SEPARATE'],
                [componentTypeEnumId: 'LSCMP_INSURANCE', description: 'Insurance', monthlyAmount: new BigDecimal('300.00'),
                 taxClassEnumId: 'LSTAX_NONTAX', revenueTreatmentEnumId: 'LSCMP_REV_SEPARATE']]

        // The lease amount without components (baseline).
        Map baseIds = contractedLease(userLogin, start, 60L, [])
        GenericValue baseQuote = from('LeaseQuote').where('leaseQuoteId', baseIds.leaseQuoteId).queryOne()

        Map ids = contractedLease(userLogin, start, 60L, components)
        GenericValue quote = from('LeaseQuote').where('leaseQuoteId', ids.leaseQuoteId).queryOne()
        // Components do not change the lease payment or the interest equivalent.
        assert quote.monthlyLeaseAmount.compareTo(baseQuote.monthlyLeaseAmount) == 0
        assert quote.totalInterestAmount.compareTo(baseQuote.totalInterestAmount) == 0

        List defs = from('LeaseQuoteComponent').where('leaseQuoteId', ids.leaseQuoteId).orderBy('componentSeqId').queryList()
        assert defs.size() == 2
        assert defs[0].componentTypeEnumId == 'LSCMP_PROP_TAX'
        assert defs[0].taxClassEnumId == 'LSTAX_TAXABLE'
        assert defs[0].monthlyAmount.compareTo(new BigDecimal('1000.00')) == 0
        assert defs[0].totalAmount.compareTo(new BigDecimal('60000.00')) == 0
        assert defs[0].totalTaxAmount.compareTo(new BigDecimal('6000.00')) == 0            // taxable: 1000 x 0.10 x 60
        assert defs[1].componentTypeEnumId == 'LSCMP_INSURANCE'
        assert defs[1].taxClassEnumId == 'LSTAX_NONTAX'
        assert defs[1].totalTaxAmount.signum() == 0                                        // non-taxable: no tax

        // Per-installment component rows: 2 components x 60 installments.
        assert from('LeasePaymentScheduleComp').where('leaseQuoteId', ids.leaseQuoteId).queryCount() == 120

        // Agreement snapshot (revision 1 before acceptance).
        assert from('LeaseAgreementComponent').where('leaseAgreementId', ids.leaseAgreementId).queryCount() == 2
        assert from('LeaseAgrPaySchedComp').where('leaseAgreementId', ids.leaseAgreementId,
                'scheduleRevisionNumber', 1L).queryCount() == 120

        // Acceptance re-snapshots the components to the new revision, re-fixing the taxable component tax.
        ok(dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: ids.leaseAgreementId, acceptanceDate: ts(start),
                userLogin: userLogin]))
        long revision = LeaseAccountingServices.currentScheduleRevision(delegator, ids.leaseAgreementId)
        assert revision == 2L
        List revComps = from('LeaseAgrPaySchedComp').where('leaseAgreementId', ids.leaseAgreementId,
                'scheduleRevisionNumber', revision, 'installmentNumber', 1L).orderBy('componentSeqId').queryList()
        assert revComps.size() == 2
        assert revComps[0].amount.compareTo(new BigDecimal('1000.00')) == 0
        assert revComps[0].taxAmount.compareTo(new BigDecimal('100.00')) == 0               // taxable at the fixed rate
        assert revComps[1].taxAmount.signum() == 0                                          // non-taxable

        // Invalid enum values are rejected.
        Map badApp = dispatcher.runSync('createLeaseApplication', [productId: 'TEST_LEASEPROD_USD',
                applicantPartyId: 'TEST_LS_SUPPLIER1', requestedTermMonths: 12L, userLogin: userLogin])
        ok(badApp)
        ok(dispatcher.runSync('createLeaseApplicationAsset', [leaseApplicationId: badApp.leaseApplicationId,
                fixedAssetTypeId: 'EQUIPMENT', assetName: 'M', quantity: BigDecimal.ONE,
                estimatedAcquisitionCost: COST, supplierPartyId: 'TEST_LS_SUPPLIER1', userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseQuote', [leaseApplicationId: badApp.leaseApplicationId,
                termMonths: 12L, components: [[componentTypeEnumId: 'NOT_A_TYPE', monthlyAmount: new BigDecimal('1.00')]],
                userLogin: userLogin]))
    }

    // Billing: separate (区分) components are a separate revenue item (403800); their taxable
    // tax is booked to tax received (224500). The lease payment still credits the asset account.
    // Payment application: tax and component before interest and principal (principal unaffected).
    void testBillingSeparateAndIncludedComponents() {
        GenericValue userLogin = admin()
        LocalDate start = LocalDate.now().minusMonths(1)
        List components = [
                // Separate taxable (property tax equivalent): 1000 + 100 tax -> 403800 / 224500.
                [componentTypeEnumId: 'LSCMP_PROP_TAX', monthlyAmount: new BigDecimal('1000.00'),
                 taxClassEnumId: 'LSTAX_TAXABLE', revenueTreatmentEnumId: 'LSCMP_REV_SEPARATE'],
                // Included non-taxable (maintenance): 200, folded into the lease payment item (128500).
                [componentTypeEnumId: 'LSCMP_MAINTENANCE', monthlyAmount: new BigDecimal('200.00'),
                 taxClassEnumId: 'LSTAX_NONTAX', revenueTreatmentEnumId: 'LSCMP_REV_INCLUDED']]
        String agreementId = activeLease(userLogin, start, 60L, components)
        List<GenericValue> rows = schedule(agreementId)
        GenericValue row1 = rows[0]

        Map bill = dispatcher.runSync('createLeaseInstallmentInvoice', [leaseAgreementId: agreementId,
                installmentNumber: 1L, userLogin: userLogin])
        ok(bill)
        // billed = lease + lease tax + components (1000 + 200) + component tax (100).
        BigDecimal expected = row1.leaseAmount.add(row1.taxAmount)
                .add(new BigDecimal('1200.00')).add(new BigDecimal('100.00'))
        assert bill.billedAmount.compareTo(expected) == 0

        GenericValue link = from('LeaseInstallmentInvoice').where('leaseAgreementId', agreementId, 'installmentNumber', 1L).queryOne()
        assert link.componentAmount.compareTo(new BigDecimal('1200.00')) == 0
        assert link.componentTaxAmount.compareTo(new BigDecimal('100.00')) == 0
        // Principal and interest are the schedule's (unchanged by components).
        assert link.principalPortion.compareTo(row1.principalPortion) == 0
        assert link.interestPortion.compareTo(row1.interestPortion) == 0

        // Invoice items: lease payment, lease tax, separate component, included component, component tax.
        List items = from('InvoiceItem').where('invoiceId', bill.invoiceId).orderBy('invoiceItemSeqId').queryList()
        List types = items.collect { it.invoiceItemTypeId }
        assert types.count { it == 'LEASE_COMPONENT' } == 2
        assert types.count { it == 'LEASE_COMP_TAX' } == 1

        // GL: Dr receivable = total; Cr 403800 (separate component), 224500 (lease tax + component tax),
        // 128500 (lease payment + included component). Balanced, no sales (403500).
        GenericValue trans = from('AcctgTrans').where('invoiceId', bill.invoiceId).queryFirst()
        assert trans.isPosted == 'Y'
        Map<String, BigDecimal> sums = entrySums(trans.acctgTransId)
        assert side(sums, 'D').compareTo(side(sums, 'C')) == 0
        assert sums['120000:D'].compareTo(expected) == 0
        assert sums['403800:C'].compareTo(new BigDecimal('1000.00')) == 0                 // separate component revenue
        assert sums['128500:C'].compareTo(row1.leaseAmount.add(new BigDecimal('200.00'))) == 0  // lease + included component
        assert sums['224500:C'].compareTo(row1.taxAmount.add(new BigDecimal('100.00'))) == 0     // lease tax + component tax
        assert sums['403500:C'] == null                                                   // no method-1 sales at billing

        // Balance: component billed and (on payment) collected; principal unaffected by components.
        Map bal = balance(agreementId, userLogin)
        assert bal.billedComponentAmount.compareTo(new BigDecimal('1200.00')) == 0
        assert bal.billedComponentTaxAmount.compareTo(new BigDecimal('100.00')) == 0
        assert bal.outstandingPrincipal.compareTo(COST) == 0

        // Pay the invoice in full: tax + component consumed before interest and principal.
        Map pay = dispatcher.runSync('receiveLeasePayment', [leaseAgreementId: agreementId, invoiceId: bill.invoiceId,
                amount: expected, userLogin: userLogin])
        ok(pay)
        bal = balance(agreementId, userLogin)
        assert bal.collectedComponentAmount.compareTo(new BigDecimal('1200.00')) == 0
        assert bal.collectedTaxAmount.compareTo(row1.taxAmount.add(new BigDecimal('100.00'))) == 0
        assert bal.collectedPrincipalAmount.compareTo(row1.principalPortion) == 0
        assert bal.outstandingPrincipal.compareTo(COST.subtract(row1.principalPortion)) == 0
    }
}
