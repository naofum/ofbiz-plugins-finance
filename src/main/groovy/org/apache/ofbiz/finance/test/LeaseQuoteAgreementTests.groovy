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
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.common.FinanceContractHelper
import org.apache.ofbiz.finance.lease.LeaseCalculator
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L2: lease payment calculation, lease quotes and lease agreements.
 *
 * Uses TEST_LEASEPROD1 (JPY, no ownership transfer, rate factor 0.018, advance,
 * taxable, 12-60 months) and TEST_LESSEE1 from FinanceTestData.xml. Each test
 * creates its own application; agreements created by a test are cancelled at
 * the end so the lessee's credit exposure does not grow across runs.
 */
class LeaseQuoteAgreementTests extends OFBizTestCase {

    LeaseQuoteAgreementTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private static BigDecimal sum(List rows, String field) {
        return rows.inject(BigDecimal.ZERO) { BigDecimal acc, row -> acc.add(row."$field" as BigDecimal) }
    }

    /** Received application with one requested asset of the given cost. */
    private String createApplication(GenericValue userLogin, BigDecimal cost = new BigDecimal('1200000'),
            long termMonths = 60L, String lesseePartyId = 'TEST_LESSEE1') {
        Map app = dispatcher.runSync('createLeaseApplication', [
                productId: 'TEST_LEASEPROD1', applicantPartyId: lesseePartyId,
                requestedTermMonths: termMonths, userLogin: userLogin])
        assert ServiceUtil.isSuccess(app), ServiceUtil.getErrorMessage(app)
        Map asset = dispatcher.runSync('createLeaseApplicationAsset', [
                leaseApplicationId: app.leaseApplicationId, fixedAssetTypeId: 'EQUIPMENT',
                assetName: 'Multifunction printer', quantity: BigDecimal.ONE,
                estimatedAcquisitionCost: cost, supplierPartyId: 'TEST_LS_SUPPLIER1', userLogin: userLogin])
        assert ServiceUtil.isSuccess(asset), ServiceUtil.getErrorMessage(asset)
        return app.leaseApplicationId
    }

    private void setAppStatus(String appId, String statusId, GenericValue userLogin) {
        Map r = dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: statusId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(r), ServiceUtil.getErrorMessage(r)
    }

    private String createUnderwriting(String appId, BigDecimal approved, GenericValue userLogin) {
        Map uw = dispatcher.runSync('createLeaseUnderwriting', [
                leaseApplicationId: appId, creditScore: 720L, approvedPrincipalAmount: approved, userLogin: userLogin])
        assert ServiceUtil.isSuccess(uw), ServiceUtil.getErrorMessage(uw)
        return uw.loanUnderwritingId
    }

    private Map approveUnderwriting(String uwId, GenericValue userLogin) {
        return dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: uwId, statusId: 'LOANUW_APPROVED', userLogin: userLogin])
    }

    private String createQuote(String appId, GenericValue userLogin, Map extra = [:]) {
        Map q = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, userLogin: userLogin] + extra)
        assert ServiceUtil.isSuccess(q), ServiceUtil.getErrorMessage(q)
        return q.leaseQuoteId
    }

    private void acceptQuote(String quoteId, GenericValue userLogin) {
        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            Map r = dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quoteId, statusId: s, userLogin: userLogin])
            assert ServiceUtil.isSuccess(r), ServiceUtil.getErrorMessage(r)
        }
    }

    /** Application approved with an approved underwriting and an accepted quote; returns [app, quote]. */
    private Map readyToContract(GenericValue userLogin, BigDecimal approved = new BigDecimal('1200000')) {
        String appId = createApplication(userLogin)
        setAppStatus(appId, 'LEASEAPP_SUBMITTED', userLogin)
        String uwId = createUnderwriting(appId, approved, userLogin)
        Map uw = approveUnderwriting(uwId, userLogin)
        assert ServiceUtil.isSuccess(uw), ServiceUtil.getErrorMessage(uw)
        setAppStatus(appId, 'LEASEAPP_APPROVED', userLogin)
        String quoteId = createQuote(appId, userLogin)
        acceptQuote(quoteId, userLogin)
        return [appId: appId, quoteId: quoteId]
    }

    private void cancelAgreement(String leaseAgreementId, GenericValue userLogin) {
        Map r = dispatcher.runSync('setLeaseAgreementStatus', [
                leaseAgreementId: leaseAgreementId, statusId: 'LEASEAGR_CANCELLED', userLogin: userLogin])
        assert ServiceUtil.isSuccess(r), ServiceUtil.getErrorMessage(r)
    }

    // ------------------------------------------------------------------
    // Calculation
    // ------------------------------------------------------------------

    // Rate factor, payments in advance, no residual (JPY, scale 0, DOWN).
    void testRateFactorCalculation() {
        LeaseCalculator.Result r = LeaseCalculator.calculate(new LeaseCalculator.Input()
                .acquisitionCost(new BigDecimal('1000000')).termMonths(60).advance(true)
                .monthlyRateFactor(new BigDecimal('0.018')).taxRate(new BigDecimal('0.10'))
                .currencyRounding(0, RoundingMode.DOWN))
        assert r.calcMethod == LeaseCalculator.METHOD_RATE_FACTOR
        assert r.monthlyLeaseAmount.compareTo(new BigDecimal('18000')) == 0
        assert r.rows.size() == 60
        assert r.totalLeaseAmount.compareTo(new BigDecimal('1080000')) == 0
        assert r.totalInterestAmount.compareTo(new BigDecimal('80000')) == 0
        assert sum(r.rows, 'principalPortion').compareTo(new BigDecimal('1000000')) == 0
        assert r.rows[0].interestPortion.signum() == 0                 // advance: no interest in the first payment
        assert r.rows[0].principalPortion.compareTo(new BigDecimal('18000')) == 0
        assert r.rows[-1].remainingPrincipal.signum() == 0
        assert r.rows[0].taxAmount.compareTo(new BigDecimal('1800')) == 0
        assert r.totalTaxAmount.compareTo(new BigDecimal('108000')) == 0
        // Solved calculation interest rate of 18000 x 60 (advance) on 1,000,000 is about 3.2% a year.
        assert r.calcInterestRate > new BigDecimal('0.030') && r.calcInterestRate < new BigDecimal('0.034')
        // Interest decreases as the principal is recovered (interest method).
        assert r.rows[1].interestPortion > r.rows[30].interestPortion
        r.rows.each { assert it.principalPortion.add(it.interestPortion).compareTo(it.leaseAmount) == 0 }
    }

    // Interest rate method, payments in arrears, with residual value.
    void testInterestRateCalculationWithResidual() {
        LeaseCalculator.Result r = LeaseCalculator.calculate(new LeaseCalculator.Input()
                .acquisitionCost(new BigDecimal('1200000')).residualValue(new BigDecimal('120000'))
                .termMonths(36).advance(false).annualCalcRate(new BigDecimal('0.03'))
                .currencyRounding(0, RoundingMode.DOWN))
        assert r.calcMethod == LeaseCalculator.METHOD_INTEREST_RATE
        assert r.calcInterestRate.compareTo(new BigDecimal('0.030000')) == 0
        assert r.rows[0].interestPortion.compareTo(new BigDecimal('3000')) == 0   // 1,200,000 x 0.25%
        assert sum(r.rows, 'principalPortion').compareTo(new BigDecimal('1080000')) == 0
        assert r.rows[-1].remainingPrincipal.compareTo(new BigDecimal('120000')) == 0
        assert r.totalInterestAmount.compareTo(r.totalLeaseAmount.subtract(new BigDecimal('1080000'))) == 0
        // Annuity of 1,200,000 - PV(120,000) over 36 months at 0.25%, rounded up to the yen.
        assert r.monthlyLeaseAmount.compareTo(new BigDecimal('31700')) >= 0
        assert r.monthlyLeaseAmount.compareTo(new BigDecimal('31715')) <= 0
        assert r.totalTaxAmount.signum() == 0

        // The same payment as a rate factor solves back to about the same rate.
        LeaseCalculator.Result back = LeaseCalculator.calculate(new LeaseCalculator.Input()
                .acquisitionCost(new BigDecimal('1200000')).residualValue(new BigDecimal('120000'))
                .termMonths(36).advance(false)
                .monthlyRateFactor(r.monthlyLeaseAmount.divide(new BigDecimal('1200000'), 10, RoundingMode.HALF_UP))
                .currencyRounding(0, RoundingMode.DOWN))
        assert back.calcInterestRate.subtract(new BigDecimal('0.03')).abs() < new BigDecimal('0.0005')
    }

    // Inconsistent inputs are rejected; a zero rate recovers exactly the principal.
    void testCalculationEdgeCases() {
        try {
            LeaseCalculator.calculate(new LeaseCalculator.Input().acquisitionCost(new BigDecimal('1000000'))
                    .termMonths(36).monthlyRateFactor(new BigDecimal('0.018')).currencyRounding(0, RoundingMode.DOWN))
            assert false, 'rate factor too low must be rejected'
        } catch (IllegalArgumentException expected) {
        }
        try {
            LeaseCalculator.calculate(new LeaseCalculator.Input().acquisitionCost(new BigDecimal('1000'))
                    .residualValue(new BigDecimal('1000')).termMonths(12).annualCalcRate(BigDecimal.ZERO))
            assert false, 'residual equal to the cost must be rejected'
        } catch (IllegalArgumentException expected) {
        }
        LeaseCalculator.Result zero = LeaseCalculator.calculate(new LeaseCalculator.Input()
                .acquisitionCost(new BigDecimal('100000')).termMonths(12).annualCalcRate(BigDecimal.ZERO)
                .currencyRounding(0, RoundingMode.DOWN))
        assert zero.monthlyLeaseAmount.compareTo(new BigDecimal('8334')) == 0          // 8333.33 rounded up
        assert sum(zero.rows, 'principalPortion').compareTo(new BigDecimal('100000')) == 0
        assert zero.rows[-1].remainingPrincipal.signum() == 0
        assert zero.rows[-1].interestPortion.signum() >= 0
    }

    // ------------------------------------------------------------------
    // Quote
    // ------------------------------------------------------------------

    void testLeaseQuoteCreationAndStatus() {
        GenericValue userLogin = admin()
        String appId = createApplication(userLogin)
        java.sql.Timestamp start = UtilDateTime.toTimestamp(4, 1, 2027, 0, 0, 0)

        String quoteId = createQuote(appId, userLogin, [leaseStartDate: start])
        GenericValue quote = from('LeaseQuote').where('leaseQuoteId', quoteId).queryOne()
        assert quote.statusId == 'LEASEQT_CREATED'
        assert quote.calcMethodEnumId == 'LEASE_CALC_RATE_FCTR'
        assert quote.acquisitionCost.compareTo(new BigDecimal('1200000')) == 0
        assert quote.monthlyLeaseAmount.compareTo(new BigDecimal('21600')) == 0      // product factor 0.018
        assert quote.taxRate.compareTo(new BigDecimal('0.10')) == 0
        assert quote.firstPaymentDate == start                                       // advance
        List rows = from('LeasePaymentSchedule').where('leaseQuoteId', quoteId).orderBy('installmentNumber').queryList()
        assert rows.size() == 60
        assert rows[0].periodFromDate == start
        assert rows[0].periodThruDate == UtilDateTime.toTimestamp(4, 30, 2027, 0, 0, 0)
        assert rows[1].periodFromDate == UtilDateTime.toTimestamp(5, 1, 2027, 0, 0, 0)
        assert sum(rows, 'principalPortion').compareTo(new BigDecimal('1200000')) == 0
        assert from('LeaseQuoteAsset').where('leaseQuoteId', quoteId).queryCount() == 1

        // Interest-rate method in arrears with a residual value; ambiguous rates are rejected.
        String quote2 = createQuote(appId, userLogin, [calcInterestRate: new BigDecimal('0.025'),
                paymentTimingEnumId: 'LEASE_PAY_ARREARS', residualValue: new BigDecimal('100000'), termMonths: 48L])
        GenericValue q2 = from('LeaseQuote').where('leaseQuoteId', quote2).queryOne()
        assert q2.calcMethodEnumId == 'LEASE_CALC_INT_RATE'
        assert q2.firstPaymentDate > q2.leaseStartDate
        List rows2 = from('LeasePaymentSchedule').where('leaseQuoteId', quote2).orderBy('installmentNumber').queryList()
        assert rows2.size() == 48
        assert rows2[-1].remainingPrincipal.compareTo(new BigDecimal('100000')) == 0
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId,
                monthlyRateFactor: new BigDecimal('0.02'), calcInterestRate: new BigDecimal('0.03'), userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId,
                termMonths: 36L, userLogin: userLogin]))                          // 0.018 x 36 cannot recover the cost
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId,
                termMonths: 6L, userLogin: userLogin]))                           // outside the product range

        // Changing the requested assets does not change an existing quote.
        Map changed = dispatcher.runSync('updateLeaseApplicationAsset', [leaseApplicationId: appId, assetSeqId: '00001',
                estimatedAcquisitionCost: new BigDecimal('1500000'), userLogin: userLogin])
        assert ServiceUtil.isSuccess(changed)
        quote.refresh()
        assert quote.acquisitionCost.compareTo(new BigDecimal('1200000')) == 0
        assert from('LeaseQuoteAsset').where('leaseQuoteId', quoteId).queryOne().acquisitionCost
                .compareTo(new BigDecimal('1200000')) == 0

        // Status workflow: no skipping, only one accepted quote per application.
        assert ServiceUtil.isError(dispatcher.runSync('setLeaseQuoteStatus', [
                leaseQuoteId: quoteId, statusId: 'LEASEQT_ACCEPTED', userLogin: userLogin]))
        acceptQuote(quoteId, userLogin)
        Map present2 = dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote2, statusId: 'LEASEQT_PRESENTED', userLogin: userLogin])
        assert ServiceUtil.isSuccess(present2)
        assert ServiceUtil.isError(dispatcher.runSync('setLeaseQuoteStatus', [
                leaseQuoteId: quote2, statusId: 'LEASEQT_ACCEPTED', userLogin: userLogin]))

        // No quote for a cancelled application.
        String cancelled = createApplication(userLogin)
        setAppStatus(cancelled, 'LEASEAPP_CANCELLED', userLogin)
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseQuote', [leaseApplicationId: cancelled, userLogin: userLogin]))
    }

    // ------------------------------------------------------------------
    // Agreement
    // ------------------------------------------------------------------

    void testCreateLeaseAgreement() {
        GenericValue userLogin = admin()
        Map ready = readyToContract(userLogin)
        Map created = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: ready.quoteId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(created), ServiceUtil.getErrorMessage(created)
        String agreementId = created.leaseAgreementId
        try {
            GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
            assert agreement.statusId == 'LEASEAGR_CONTRACTED'
            assert agreement.lesseePartyId == 'TEST_LESSEE1'
            assert agreement.supplierPartyId == 'TEST_LS_SUPPLIER1'
            assert agreement.organizationPartyId == 'Company'
            assert agreement.leaseClassEnumId == 'LEASE_CLS_FIN_NONTR'
            assert agreement.revenueMethodEnumId == 'LEASE_REV_AT_START'
            assert agreement.monthlyLeaseAmount.compareTo(new BigDecimal('21600')) == 0
            assert agreement.leaseStartDate == null

            GenericValue contract = from('FinanceContract').where('financeContractId', created.financeContractId).queryOne()
            assert contract.contractTypeId == 'LEASE'
            assert contract.statusId == 'FINCTR_CONTRACTED'
            assert contract.customerPartyId == 'TEST_LESSEE1'
            assert contract.principalAmount.compareTo(new BigDecimal('1200000')) == 0
            assert agreement.financeContractId == contract.financeContractId

            List schedule = from('LeaseAgreementPaymentSchedule')
                    .where('leaseAgreementId', agreementId, 'scheduleRevisionNumber', 1L).queryList()
            assert schedule.size() == 60
            assert sum(schedule, 'principalPortion').compareTo(new BigDecimal('1200000')) == 0
            assert from('LeaseAgreementAsset').where('leaseAgreementId', agreementId).queryCount() == 1
            assert from('LeaseApplication').where('leaseApplicationId', ready.appId).queryOne().statusId == 'LEASEAPP_CONTRACTED'

            // One agreement per quote.
            assert ServiceUtil.isError(dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: ready.quoteId, userLogin: userLogin]))

            // The common customer summary resolves the lease agreement through the adapter.
            Map summary = dispatcher.runSync('getFinanceCustomerSummary', [customerPartyId: 'TEST_LESSEE1', userLogin: userLogin])
            assert ServiceUtil.isSuccess(summary)
            Map row = summary.contracts.find { it.financeContractId == contract.financeContractId }
            assert row.agreementId == agreementId
            assert summary.activeExposure >= new BigDecimal('1200000')   // CONTRACTED counts as exposure

            // Start (ACTIVE) is not reachable from the status service; cancel is.
            assert ServiceUtil.isError(dispatcher.runSync('setLeaseAgreementStatus', [
                    leaseAgreementId: agreementId, statusId: 'LEASEAGR_ACTIVE', userLogin: userLogin]))
        } finally {
            cancelAgreement(agreementId, userLogin)
        }
        assert from('FinanceContract').where('financeContractId', created.financeContractId).queryOne().statusId == 'FINCTR_CANCELLED'
    }

    void testLeaseAgreementPreconditions() {
        GenericValue userLogin = admin()

        // Quote not accepted / application not approved / no approved underwriting.
        String appId = createApplication(userLogin)
        String quoteId = createQuote(appId, userLogin)
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quoteId, userLogin: userLogin]))
        acceptQuote(quoteId, userLogin)
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quoteId, userLogin: userLogin]))
        setAppStatus(appId, 'LEASEAPP_SUBMITTED', userLogin)
        setAppStatus(appId, 'LEASEAPP_APPROVED', userLogin)
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quoteId, userLogin: userLogin]))
        assert from('LeaseAgreement').where('leaseQuoteId', quoteId).queryCount() == 0

        // Approved amount below the acquisition cost.
        Map low = readyToContract(userLogin, new BigDecimal('1000000'))
        assert ServiceUtil.isError(dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: low.quoteId, userLogin: userLogin]))

        // Lease underwriting approval requires an approved amount.
        String noAmountApp = createApplication(userLogin)
        setAppStatus(noAmountApp, 'LEASEAPP_SUBMITTED', userLogin)
        String uwId = createUnderwriting(noAmountApp, null, userLogin)
        assert ServiceUtil.isError(approveUnderwriting(uwId, userLogin))
    }

    // Credit line of the lessee: loan + lease exposure (CONTRACTED and ACTIVE) plus the new cost.
    void testLeaseCreditLimit() {
        GenericValue userLogin = admin()
        // A lessee of its own per run: credit lines are customer master data and must not
        // leak into the other tests through the shared test database.
        String lessee = 'TEST_LSCR_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Lease credit test lessee'])
        delegator.create('CreditLine', [creditLineId: delegator.getNextSeqId('CreditLine'), creditLineTypeId: 'REVOLVING',
                partyId: lessee, currencyUomId: 'JPY', fromDate: UtilDateTime.addDaysToTimestamp(UtilDateTime.nowTimestamp(), -1),
                creditLimitAmount: new BigDecimal('1200000'), description: 'Lease credit test'])
        String agreementId = null
        try {
            // Over the limit at underwriting approval.
            String overApp = createApplication(userLogin, new BigDecimal('1200000'), 60L, lessee)
            setAppStatus(overApp, 'LEASEAPP_SUBMITTED', userLogin)
            String overUw = createUnderwriting(overApp, new BigDecimal('1200001'), userLogin)
            assert ServiceUtil.isError(approveUnderwriting(overUw, userLogin))

            // Within the limit: approved, quoted, accepted.
            String appId = createApplication(userLogin, new BigDecimal('1200000'), 60L, lessee)
            setAppStatus(appId, 'LEASEAPP_SUBMITTED', userLogin)
            String uwId = createUnderwriting(appId, new BigDecimal('1200000'), userLogin)
            Map approved = approveUnderwriting(uwId, userLogin)
            assert ServiceUtil.isSuccess(approved), ServiceUtil.getErrorMessage(approved)
            setAppStatus(appId, 'LEASEAPP_APPROVED', userLogin)
            String quoteId = createQuote(appId, userLogin)
            acceptQuote(quoteId, userLogin)

            // Another contract consumed the limit in the meantime -> agreement rejected.
            String other = FinanceContractHelper.createContract(delegator, 'LOAN', lessee, 'TEST_LOANPROD1',
                    'JPY', BigDecimal.ONE, UtilDateTime.nowTimestamp(), 'FINCTR_ACTIVE')
            try {
                assert ServiceUtil.isError(dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quoteId, userLogin: userLogin]))
            } finally {
                FinanceContractHelper.updateStatus(delegator, other, 'FINCTR_CANCELLED')
            }
            Map created = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quoteId, userLogin: userLogin])
            assert ServiceUtil.isSuccess(created), ServiceUtil.getErrorMessage(created)
            agreementId = created.leaseAgreementId
            // The CONTRACTED lease counts as exposure.
            assert FinanceContractHelper.sumExposurePrincipal(delegator, lessee).compareTo(new BigDecimal('1200000')) == 0
        } finally {
            if (agreementId) {
                cancelAgreement(agreementId, userLogin)
            }
        }
        assert FinanceContractHelper.sumExposurePrincipal(delegator, lessee).signum() == 0
    }
}