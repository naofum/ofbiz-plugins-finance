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
 * Tests for the business loan flow added in STEP 5: credit line CRUD, optional
 * guarantor recording, and the credit-limit check enforced at underwriting
 * approval and contract creation.
 */
class LoanBusinessLoanTests extends OFBizTestCase {

    LoanBusinessLoanTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private String createBusinessApplication(BigDecimal principal, GenericValue userLogin) {
        Map result = dispatcher.runSync('createLoanApplication', [
                productId               : 'TEST_BIZPROD1',
                applicantPartyId        : 'TEST_BUSINESS1',
                currencyUomId           : 'USD',
                requestedPrincipalAmount: principal,
                requestedTermMonths     : 12L,
                userLogin               : userLogin])
        assert ServiceUtil.isSuccess(result)
        return result.loanApplicationId
    }

    private String createUnderwriting(String loanApplicationId, BigDecimal principal, GenericValue userLogin) {
        Map created = dispatcher.runSync('createLoanUnderwriting', [
                loanApplicationId          : loanApplicationId,
                statusId                  : 'LOANUW_IN_REVIEW',
                creditScore               : 700L,
                decision                  : 'PENDING',
                approvedPrincipalAmount   : principal,
                approvedAnnualInterestRate: new BigDecimal('0.100000'),
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

    private String createQuote(String loanApplicationId, BigDecimal principal, GenericValue userLogin) {
        Map result = dispatcher.runSync('createLoanQuote', [
                loanApplicationId : loanApplicationId,
                principalAmount   : principal,
                annualInterestRate: new BigDecimal('0.100000'),
                termMonths        : 12L,
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

    // Credit line CRUD works through the finance services.
    void testCreditLineCrud() {
        GenericValue userLogin = admin()
        Map created = dispatcher.runSync('createCreditLine', [
                creditLineTypeId : 'REVOLVING',
                partyId          : 'TEST_BUSINESS1',
                currencyUomId    : 'USD',
                creditLimitAmount: new BigDecimal('50000.00'),
                description      : 'Test credit line',
                userLogin        : userLogin])
        assert ServiceUtil.isSuccess(created)
        String creditLineId = created.creditLineId
        assert creditLineId != null

        assert ServiceUtil.isSuccess(dispatcher.runSync('updateCreditLine', [
                creditLineId     : creditLineId,
                creditLimitAmount: new BigDecimal('75000.00'),
                userLogin        : userLogin]))
        assert from('CreditLine').where('creditLineId', creditLineId).queryOne()
                .getBigDecimal('creditLimitAmount').compareTo(new BigDecimal('75000.00')) == 0

        assert ServiceUtil.isSuccess(dispatcher.runSync('deleteCreditLine', [
                creditLineId: creditLineId, userLogin: userLogin]))
        assert from('CreditLine').where('creditLineId', creditLineId).queryOne() == null
    }

    // Guarantors can be recorded and removed while the application is RECEIVED.
    void testGuarantorAssignment() {
        GenericValue userLogin = admin()
        String appId = createBusinessApplication(new BigDecimal('60000.00'), userLogin)

        assert ServiceUtil.isSuccess(dispatcher.runSync('assignLoanGuarantor', [
                loanApplicationId  : appId,
                guarantorPartyId   : 'TEST_BORROWER1',
                guaranteeTypeEnumId: 'GUAR_JOINT',
                userLogin          : userLogin]))
        assert from('LoanGuarantor')
                .where('loanApplicationId', appId, 'guarantorPartyId', 'TEST_BORROWER1').queryOne() != null

        assert ServiceUtil.isSuccess(dispatcher.runSync('removeLoanGuarantor', [
                loanApplicationId: appId, guarantorPartyId: 'TEST_BORROWER1', userLogin: userLogin]))
        assert from('LoanGuarantor')
                .where('loanApplicationId', appId, 'guarantorPartyId', 'TEST_BORROWER1').queryOne() == null

        assert ServiceUtil.isSuccess(dispatcher.runSync('assignLoanGuarantor', [
                loanApplicationId: appId, guarantorPartyId: 'TEST_BORROWER1', userLogin: userLogin]))
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanApplicationStatus', [
                loanApplicationId: appId, statusId: 'LOANAPP_SUBMITTED', userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('removeLoanGuarantor', [
                loanApplicationId: appId, guarantorPartyId: 'TEST_BORROWER1', userLogin: userLogin]))
    }

    // Underwriting approval rejects a business loan that exceeds the credit line.
    void testCreditLimitExceededRejected() {
        GenericValue userLogin = admin()
        String appId = createBusinessApplication(new BigDecimal('120000.00'), userLogin)
        String uwId = createUnderwriting(appId, new BigDecimal('120000.00'), userLogin)
        Map rejected = dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: uwId, statusId: 'LOANUW_APPROVED', userLogin: userLogin])
        assert ServiceUtil.isError(rejected)
    }

    // Underwriting approval allows a business loan within the credit line.
    void testCreditLimitWithinApproved() {
        GenericValue userLogin = admin()
        String appId = createBusinessApplication(new BigDecimal('60000.00'), userLogin)
        String uwId = createUnderwriting(appId, new BigDecimal('60000.00'), userLogin)
        Map approved = dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: uwId, statusId: 'LOANUW_APPROVED', userLogin: userLogin])
        assert ServiceUtil.isSuccess(approved)
    }

    // The credit-limit check counts the existing ACTIVE exposure.
    void testCreditLimitConsidersExistingExposure() {
        GenericValue userLogin = admin()
        String appId = createBusinessApplication(new BigDecimal('60000.00'), userLogin)
        approveApplication(appId, userLogin)
        String uwId = createUnderwriting(appId, new BigDecimal('60000.00'), userLogin)
        assert ServiceUtil.isSuccess(dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: uwId, statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
        String loanQuoteId = createQuote(appId, new BigDecimal('60000.00'), userLogin)
        acceptQuote(loanQuoteId, userLogin)
        assert ServiceUtil.isSuccess(dispatcher.runSync('createLoanAgreement', [
                loanQuoteId: loanQuoteId, userLogin: userLogin]))

        // A second 60000 loan would bring the total exposure to 120000 > 100000.
        String secondAppId = createBusinessApplication(new BigDecimal('60000.00'), userLogin)
        String secondUwId = createUnderwriting(secondAppId, new BigDecimal('60000.00'), userLogin)
        Map rejected = dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: secondUwId, statusId: 'LOANUW_APPROVED', userLogin: userLogin])
        assert ServiceUtil.isError(rejected)
    }
}
