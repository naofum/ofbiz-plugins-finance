/*******************************************************************************
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
 *******************************************************************************/
package org.apache.ofbiz.finance.test

import java.math.BigDecimal
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * Tests for the secured (collateralized) loan flow added in STEP 4:
 * collateral master CRUD, application declaration, valuation, LTV enforcement
 * at underwriting approval, and lien creation at contract time.
 *
 * Relies on seed data (collateral types/status/valuation methods) and test data
 * (secured financial product, collateral and borrower) loaded by FinanceTestData.xml.
 */
class LoanCollateralTests extends OFBizTestCase {

    LoanCollateralTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private String createCollateral(GenericValue userLogin) {
        Map result = dispatcher.runSync('createCollateral', [
                collateralTypeId: 'REAL_ESTATE',
                ownerPartyId    : 'TEST_BORROWER1',
                currencyUomId   : 'USD',
                description     : 'Test collateral',
                userLogin       : userLogin])
        assert ServiceUtil.isSuccess(result)
        return result.collateralId
    }

    private String createSecuredApplication(GenericValue userLogin) {
        Map result = dispatcher.runSync('createLoanApplication', [
                productId               : 'TEST_SECPROD1',
                applicantPartyId        : 'TEST_BORROWER1',
                currencyUomId           : 'USD',
                requestedPrincipalAmount: new BigDecimal('60000.00'),
                requestedTermMonths     : 10L,
                userLogin               : userLogin])
        assert ServiceUtil.isSuccess(result)
        return result.loanApplicationId
    }

    private void declareCollateral(String loanApplicationId, String collateralId, GenericValue userLogin) {
        Map result = dispatcher.runSync('assignLoanApplicationCollateral', [
                loanApplicationId: loanApplicationId, collateralId: collateralId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(result)
    }

    private void valueCollateral(String collateralId, BigDecimal amount,
            java.sql.Timestamp date, GenericValue userLogin) {
        Map result = dispatcher.runSync('createCollateralValuation', [
                collateralId         : collateralId,
                appraisedValueAmount : amount,
                valuationDate        : date,
                valuationMethodEnumId: 'COLL_VAL_APPRAISAL',
                userLogin            : userLogin])
        assert ServiceUtil.isSuccess(result)
    }

    private String createUnderwriting(String loanApplicationId, BigDecimal principal, GenericValue userLogin) {
        Map created = dispatcher.runSync('createLoanUnderwriting', [
                loanApplicationId          : loanApplicationId,
                statusId                  : 'LOANUW_IN_REVIEW',
                creditScore               : 750L,
                decision                  : 'PENDING',
                approvedPrincipalAmount   : principal,
                approvedAnnualInterestRate: new BigDecimal('0.120000'),
                userLogin                  : userLogin])
        assert ServiceUtil.isSuccess(created)
        return created.loanUnderwritingId
    }

    private void approveApplication(String loanApplicationId, GenericValue userLogin) {
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanApplicationStatus', [
                loanApplicationId: loanApplicationId, statusId: 'LOANAPP_SUBMITTED', userLogin: userLogin]))
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanApplicationStatus', [
                loanApplicationId: loanApplicationId, statusId: 'LOANAPP_APPROVED', userLogin: userLogin]))
    }

    private String createQuote(String loanApplicationId, BigDecimal principal, long termMonths,
            GenericValue userLogin) {
        Map result = dispatcher.runSync('createLoanQuote', [
                loanApplicationId : loanApplicationId,
                principalAmount   : principal,
                annualInterestRate: new BigDecimal('0.120000'),
                termMonths        : termMonths,
                userLogin         : userLogin])
        assert ServiceUtil.isSuccess(result)
        return result.loanQuoteId
    }

    private void acceptQuote(String loanQuoteId, GenericValue userLogin) {
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanQuoteStatus', [
                loanQuoteId: loanQuoteId, statusId: 'LOANQT_PRESENTED', userLogin: userLogin]))
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanQuoteStatus', [
                loanQuoteId: loanQuoteId, statusId: 'LOANQT_ACCEPTED', userLogin: userLogin]))
    }

    // Collateral is created REGISTERED, editable until released, and only
    // deletable while not assigned to a loan.
    void testCollateralCrudAndStatus() {
        GenericValue userLogin = admin()
        String collateralId = createCollateral(userLogin)
        assert from('Collateral').where('collateralId', collateralId).queryOne().statusId == 'COLL_REGISTERED'

        assert ServiceUtil.isSuccess(dispatcher.runSync('updateCollateral', [
                collateralId: collateralId, description: 'Updated collateral', userLogin: userLogin]))
        assert from('Collateral').where('collateralId', collateralId).queryOne().description == 'Updated collateral'

        assert ServiceUtil.isSuccess(dispatcher.runSync('setCollateralStatus', [
                collateralId: collateralId, statusId: 'COLL_RELEASED', userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('updateCollateral', [
                collateralId: collateralId, description: 'Must not change', userLogin: userLogin]))

        String deletable = createCollateral(userLogin)
        assert ServiceUtil.isSuccess(dispatcher.runSync('deleteCollateral', [
                collateralId: deletable, userLogin: userLogin]))
        assert from('Collateral').where('collateralId', deletable).queryOne() == null
    }

    // A collateral declaration can only change while the application is RECEIVED.
    void testApplicationCollateralDeclaration() {
        GenericValue userLogin = admin()
        String appId = createSecuredApplication(userLogin)
        String collateralId = createCollateral(userLogin)

        declareCollateral(appId, collateralId, userLogin)
        assert from('LoanApplicationCollateral')
                .where('loanApplicationId', appId, 'collateralId', collateralId).queryOne() != null

        assert ServiceUtil.isSuccess(dispatcher.runSync('removeLoanApplicationCollateral', [
                loanApplicationId: appId, collateralId: collateralId, userLogin: userLogin]))
        assert from('LoanApplicationCollateral')
                .where('loanApplicationId', appId, 'collateralId', collateralId).queryOne() == null

        declareCollateral(appId, collateralId, userLogin)

        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanApplicationStatus', [
                loanApplicationId: appId, statusId: 'LOANAPP_SUBMITTED', userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('removeLoanApplicationCollateral', [
                loanApplicationId: appId, collateralId: collateralId, userLogin: userLogin]))
    }

    // A valuation is recorded and the latest one is the authoritative value.
    void testCollateralValuation() {
        GenericValue userLogin = admin()
        String collateralId = createCollateral(userLogin)
        valueCollateral(collateralId, new BigDecimal('95000.00'),
                java.sql.Timestamp.valueOf('2026-04-01 00:00:00'), userLogin)
        GenericValue latest = from('CollateralValuation')
                .where('collateralId', collateralId).orderBy('-valuationDate').queryFirst()
        assert latest != null
        assert latest.appraisedValueAmount.compareTo(new BigDecimal('95000.00')) == 0
    }

    // A secured application with no collateral must not be approved.
    void testSecuredRequiresCollateral() {
        GenericValue userLogin = admin()
        String appId = createSecuredApplication(userLogin)
        String uwId = createUnderwriting(appId, new BigDecimal('60000.00'), userLogin)
        Map rejected = dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: uwId, statusId: 'LOANUW_APPROVED', userLogin: userLogin])
        assert ServiceUtil.isError(rejected)
    }

    // Underwriting approval enforces the product's LTV cap against the latest
    // appraised value of the declared collateral.
    void testUnderwritingLtvEnforcement() {
        GenericValue userLogin = admin()
        String appId = createSecuredApplication(userLogin)
        String collateralId = createCollateral(userLogin)
        declareCollateral(appId, collateralId, userLogin)

        // 60000 / 50000 = 120% exceeds the 80% cap.
        valueCollateral(collateralId, new BigDecimal('50000.00'),
                java.sql.Timestamp.valueOf('2026-01-01 00:00:00'), userLogin)
        String uwId = createUnderwriting(appId, new BigDecimal('60000.00'), userLogin)
        Map rejected = dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: uwId, statusId: 'LOANUW_APPROVED', userLogin: userLogin])
        assert ServiceUtil.isError(rejected)

        // 60000 / 100000 = 60% is within the cap.
        valueCollateral(collateralId, new BigDecimal('100000.00'),
                java.sql.Timestamp.valueOf('2026-02-01 00:00:00'), userLogin)
        Map approved = dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: uwId, statusId: 'LOANUW_APPROVED', userLogin: userLogin])
        assert ServiceUtil.isSuccess(approved)
    }

    // Contract creation links declared collateral and can be released on close.
    void testSecuredAgreementCreatesLien() {
        GenericValue userLogin = admin()
        String appId = createSecuredApplication(userLogin)
        String collateralId = createCollateral(userLogin)
        declareCollateral(appId, collateralId, userLogin)
        valueCollateral(collateralId, new BigDecimal('100000.00'),
                java.sql.Timestamp.valueOf('2026-03-01 00:00:00'), userLogin)

        approveApplication(appId, userLogin)
        String uwId = createUnderwriting(appId, new BigDecimal('60000.00'), userLogin)
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: uwId, statusId: 'LOANUW_APPROVED', userLogin: userLogin]))

        String loanQuoteId = createQuote(appId, new BigDecimal('60000.00'), 10L, userLogin)
        acceptQuote(loanQuoteId, userLogin)

        Map agrResult = dispatcher.runSync('createLoanAgreement', [loanQuoteId: loanQuoteId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(agrResult)
        String loanAgreementId = agrResult.loanAgreementId

        List liens = from('LoanCollateral').where('loanAgreementId', loanAgreementId).queryList()
        assert liens.size() == 1
        assert liens[0].collateralId == collateralId
        assert liens[0].assignedValueAmount.compareTo(new BigDecimal('100000.00')) == 0
        assert liens[0].thruDate == null

        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanAgreementStatus', [
                loanAgreementId: loanAgreementId, statusId: 'LOANAGR_CLOSED', userLogin: userLogin]))
        assert ServiceUtil.isSuccess(dispatcher.runSync('releaseLoanCollateral', [
                loanAgreementId: loanAgreementId, collateralId: collateralId, userLogin: userLogin]))
        assert from('LoanCollateral')
                .where('loanAgreementId', loanAgreementId, 'collateralId', collateralId).queryOne().thruDate != null
    }
}
