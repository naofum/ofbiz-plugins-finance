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
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L1: lease products, lease applications, requested assets and the
 * common underwriting used with applicationTypeId=LEASE.
 *
 * Uses TEST_LEASEPROD1 / TEST_LESSEE1 / TEST_LS_SUPPLIER1 from FinanceTestData.xml.
 * Every test creates its own applications so reruns do not depend on earlier state.
 */
class LeaseApplicationTests extends OFBizTestCase {

    LeaseApplicationTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private String createApplication(GenericValue userLogin, long termMonths = 36L) {
        Map result = dispatcher.runSync('createLeaseApplication', [
                productId          : 'TEST_LEASEPROD1',
                applicantPartyId   : 'TEST_LESSEE1',
                requestedTermMonths: termMonths,
                purpose            : 'Lease application test',
                userLogin          : userLogin])
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
        return result.leaseApplicationId
    }

    private Map addAsset(String leaseApplicationId, GenericValue userLogin, String fixedAssetTypeId = 'EQUIPMENT',
            BigDecimal cost = new BigDecimal('1200000')) {
        return dispatcher.runSync('createLeaseApplicationAsset', [
                leaseApplicationId      : leaseApplicationId,
                fixedAssetTypeId        : fixedAssetTypeId,
                assetName               : 'Multifunction printer',
                quantity                : BigDecimal.ONE,
                estimatedAcquisitionCost: cost,
                supplierPartyId         : 'TEST_LS_SUPPLIER1',
                userLogin               : userLogin])
    }

    private void setStatus(String leaseApplicationId, String statusId, GenericValue userLogin) {
        Map result = dispatcher.runSync('setLeaseApplicationStatus', [
                leaseApplicationId: leaseApplicationId, statusId: statusId, userLogin: userLogin])
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    // Lease terms can be added only to lease-type products; class is derived from the type.
    void testLeaseProductCrud() {
        GenericValue userLogin = admin()
        Map created = dispatcher.runSync('createFinanceProduct', [
                productName           : 'Test Vehicle Lease',
                financialProductTypeId: 'LEASE_FIN_TRANSFER',
                currencyUomId         : 'JPY',
                minTermMonths         : 24L,
                maxTermMonths         : 72L,
                userLogin             : userLogin])
        assert ServiceUtil.isSuccess(created), ServiceUtil.getErrorMessage(created)
        String productId = created.productId
        assert from('Product').where('productId', productId).queryOne() != null

        // A class that does not match the product type and an invalid residual rate are rejected.
        Map mismatch = dispatcher.runSync('createFinancialProductLease', [
                productId: productId, leaseClassEnumId: 'LEASE_CLS_FIN_NONTR', userLogin: userLogin])
        assert ServiceUtil.isError(mismatch)
        Map badResidual = dispatcher.runSync('createFinancialProductLease', [
                productId: productId, residualValueRate: new BigDecimal('1.5'), userLogin: userLogin])
        assert ServiceUtil.isError(badResidual)

        Map terms = dispatcher.runSync('createFinancialProductLease', [
                productId: productId, defaultCalcInterestRate: new BigDecimal('0.030000'),
                residualValueRate: new BigDecimal('0.100000'), userLogin: userLogin])
        assert ServiceUtil.isSuccess(terms), ServiceUtil.getErrorMessage(terms)
        GenericValue lease = from('FinancialProductLease').where('productId', productId).queryOne()
        assert lease.leaseClassEnumId == 'LEASE_CLS_FIN_TRANS'
        assert lease.paymentTimingEnumId == 'LEASE_PAY_ADVANCE'
        assert lease.revenueMethodEnumId == 'LEASE_REV_AT_START'
        assert lease.taxable == 'Y'

        Map duplicate = dispatcher.runSync('createFinancialProductLease', [productId: productId, userLogin: userLogin])
        assert ServiceUtil.isError(duplicate)

        Map updated = dispatcher.runSync('updateFinancialProductLease', [
                productId: productId, paymentTimingEnumId: 'LEASE_PAY_ARREARS', taxable: 'N', userLogin: userLogin])
        assert ServiceUtil.isSuccess(updated), ServiceUtil.getErrorMessage(updated)
        lease.refresh()
        assert lease.paymentTimingEnumId == 'LEASE_PAY_ARREARS'
        assert lease.taxable == 'N'
        assert lease.residualValueRate.compareTo(new BigDecimal('0.1')) == 0
        Map badTiming = dispatcher.runSync('updateFinancialProductLease', [
                productId: productId, paymentTimingEnumId: 'LOAN_RPM_EQUAL_PAY', userLogin: userLogin])
        assert ServiceUtil.isError(badTiming)

        Map assetType = dispatcher.runSync('addFinancialProductLeaseAssetType', [
                productId: productId, fixedAssetTypeId: 'VEHICLE', userLogin: userLogin])
        assert ServiceUtil.isSuccess(assetType)

        // A loan product cannot get lease terms.
        Map loanProduct = dispatcher.runSync('createFinancialProductLease', [
                productId: 'TEST_LOANPROD1', userLogin: userLogin])
        assert ServiceUtil.isError(loanProduct)
        assert from('FinancialProductLease').where('productId', 'TEST_LOANPROD1').queryOne() == null

        // The shared product view lists lease and loan products by business.
        assert from('FinancialProductAndType').where('productId', productId).queryOne().parentTypeId == 'LEASE'
        assert from('FinancialProductAndType').where('productId', 'TEST_LOANPROD1').queryOne().parentTypeId == 'LOAN'
    }

    // Applications start RECEIVED, are editable only while RECEIVED, and use the status service.
    void testLeaseApplicationCrudAndStatus() {
        GenericValue userLogin = admin()
        Map withStatus = dispatcher.runSync('createLeaseApplication', [
                productId: 'TEST_LEASEPROD1', applicantPartyId: 'TEST_LESSEE1', requestedTermMonths: 36L,
                statusId: 'LEASEAPP_APPROVED', userLogin: userLogin])
        assert ServiceUtil.isSuccess(withStatus)
        GenericValue app = from('LeaseApplication').where('leaseApplicationId', withStatus.leaseApplicationId).queryOne()
        assert app.statusId == 'LEASEAPP_RECEIVED'
        assert app.currencyUomId == 'JPY'
        assert from('LeaseApplicationStatus').where('leaseApplicationId', app.leaseApplicationId).queryCount() == 1

        // Loan products and out-of-range terms are rejected.
        Map loanProduct = dispatcher.runSync('createLeaseApplication', [
                productId: 'TEST_LOANPROD1', applicantPartyId: 'TEST_LESSEE1', requestedTermMonths: 36L,
                userLogin: userLogin])
        assert ServiceUtil.isError(loanProduct)
        Map tooLong = dispatcher.runSync('createLeaseApplication', [
                productId: 'TEST_LEASEPROD1', applicantPartyId: 'TEST_LESSEE1', requestedTermMonths: 120L,
                userLogin: userLogin])
        assert ServiceUtil.isError(tooLong)

        Map updated = dispatcher.runSync('updateLeaseApplication', [
                leaseApplicationId: app.leaseApplicationId, requestedTermMonths: 48L, statusId: 'LEASEAPP_APPROVED',
                userLogin: userLogin])
        assert ServiceUtil.isSuccess(updated)
        app.refresh()
        assert app.requestedTermMonths == 48L
        assert app.statusId == 'LEASEAPP_RECEIVED'
        Map badUpdate = dispatcher.runSync('updateLeaseApplication', [
                leaseApplicationId: app.leaseApplicationId, requestedTermMonths: 6L, userLogin: userLogin])
        assert ServiceUtil.isError(badUpdate)

        // Invalid transitions are rejected; valid ones are recorded.
        Map skip = dispatcher.runSync('setLeaseApplicationStatus', [
                leaseApplicationId: app.leaseApplicationId, statusId: 'LEASEAPP_CONTRACTED', userLogin: userLogin])
        assert ServiceUtil.isError(skip)
        setStatus(app.leaseApplicationId, 'LEASEAPP_SUBMITTED', userLogin)
        assert from('LeaseApplicationStatus').where('leaseApplicationId', app.leaseApplicationId).queryCount() == 2

        Map afterSubmit = dispatcher.runSync('updateLeaseApplication', [
                leaseApplicationId: app.leaseApplicationId, purpose: 'changed', userLogin: userLogin])
        assert ServiceUtil.isError(afterSubmit)
        Map deleteSubmitted = dispatcher.runSync('deleteLeaseApplication', [
                leaseApplicationId: app.leaseApplicationId, userLogin: userLogin])
        assert ServiceUtil.isError(deleteSubmitted)

        // A received application without underwriting can be deleted with its assets.
        String other = createApplication(userLogin)
        assert ServiceUtil.isSuccess(addAsset(other, userLogin))
        Map deleted = dispatcher.runSync('deleteLeaseApplication', [leaseApplicationId: other, userLogin: userLogin])
        assert ServiceUtil.isSuccess(deleted), ServiceUtil.getErrorMessage(deleted)
        assert from('LeaseApplication').where('leaseApplicationId', other).queryOne() == null
        assert from('LeaseApplicationAsset').where('leaseApplicationId', other).queryCount() == 0
    }

    // Requested assets: allowed types only, positive amounts, editable only while RECEIVED.
    void testLeaseApplicationAssets() {
        GenericValue userLogin = admin()
        String appId = createApplication(userLogin)

        Map first = addAsset(appId, userLogin)
        assert ServiceUtil.isSuccess(first), ServiceUtil.getErrorMessage(first)
        assert first.assetSeqId == '00001'
        Map second = addAsset(appId, userLogin, 'EQUIPMENT', new BigDecimal('300000'))
        assert second.assetSeqId == '00002'

        assert ServiceUtil.isError(addAsset(appId, userLogin, 'VEHICLE'))          // not allowed by the product
        assert ServiceUtil.isError(addAsset(appId, userLogin, 'EQUIPMENT', BigDecimal.ZERO))

        Map updated = dispatcher.runSync('updateLeaseApplicationAsset', [
                leaseApplicationId: appId, assetSeqId: '00002', quantity: new BigDecimal('2'),
                estimatedAcquisitionCost: new BigDecimal('600000'), userLogin: userLogin])
        assert ServiceUtil.isSuccess(updated), ServiceUtil.getErrorMessage(updated)
        GenericValue asset = from('LeaseApplicationAsset').where('leaseApplicationId', appId, 'assetSeqId', '00002').queryOne()
        assert asset.quantity.compareTo(new BigDecimal('2')) == 0
        assert asset.supplierPartyId == 'TEST_LS_SUPPLIER1'

        Map removed = dispatcher.runSync('removeLeaseApplicationAsset', [
                leaseApplicationId: appId, assetSeqId: '00002', userLogin: userLogin])
        assert ServiceUtil.isSuccess(removed)
        assert from('LeaseApplicationAsset').where('leaseApplicationId', appId).queryCount() == 1

        setStatus(appId, 'LEASEAPP_SUBMITTED', userLogin)
        assert ServiceUtil.isError(addAsset(appId, userLogin))
        Map removeAfterSubmit = dispatcher.runSync('removeLeaseApplicationAsset', [
                leaseApplicationId: appId, assetSeqId: '00001', userLogin: userLogin])
        assert ServiceUtil.isError(removeAfterSubmit)
    }

    // Approval (application and underwriting) requires at least one requested asset.
    void testLeaseApprovalRequiresAssets() {
        GenericValue userLogin = admin()

        String emptyApp = createApplication(userLogin)
        setStatus(emptyApp, 'LEASEAPP_SUBMITTED', userLogin)
        Map uw = dispatcher.runSync('createLeaseUnderwriting', [
                leaseApplicationId: emptyApp, creditScore: 700L,
                approvedPrincipalAmount: new BigDecimal('1000000'), userLogin: userLogin])
        assert ServiceUtil.isSuccess(uw), ServiceUtil.getErrorMessage(uw)
        GenericValue underwriting = from('LoanUnderwriting').where('loanUnderwritingId', uw.loanUnderwritingId).queryOne()
        assert underwriting.applicationTypeId == 'LEASE'
        assert underwriting.applicationId == emptyApp
        assert underwriting.loanApplicationId == null
        assert underwriting.statusId == 'LOANUW_IN_REVIEW'
        assert underwriting.decision == 'PENDING'

        Map approveUw = dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: uw.loanUnderwritingId, statusId: 'LOANUW_APPROVED', userLogin: userLogin])
        assert ServiceUtil.isError(approveUw)
        underwriting.refresh()
        assert underwriting.statusId == 'LOANUW_IN_REVIEW'
        Map approveApp = dispatcher.runSync('setLeaseApplicationStatus', [
                leaseApplicationId: emptyApp, statusId: 'LEASEAPP_APPROVED', userLogin: userLogin])
        assert ServiceUtil.isError(approveApp)

        // With an asset, the common review approves and the application can be approved.
        String appId = createApplication(userLogin)
        assert ServiceUtil.isSuccess(addAsset(appId, userLogin))
        setStatus(appId, 'LEASEAPP_SUBMITTED', userLogin)
        Map uw2 = dispatcher.runSync('createLeaseUnderwriting', [
                leaseApplicationId: appId, creditScore: 720L,
                approvedPrincipalAmount: new BigDecimal('1200000'), userLogin: userLogin])
        assert ServiceUtil.isSuccess(uw2)
        Map approved = dispatcher.runSync('setLoanUnderwritingStatus', [
                loanUnderwritingId: uw2.loanUnderwritingId, statusId: 'LOANUW_APPROVED', userLogin: userLogin])
        assert ServiceUtil.isSuccess(approved), ServiceUtil.getErrorMessage(approved)
        GenericValue approvedUw = from('LoanUnderwriting').where('loanUnderwritingId', uw2.loanUnderwritingId).queryOne()
        assert approvedUw.decision == 'APPROVED'
        assert approvedUw.reviewDate != null
        Map changeApproved = dispatcher.runSync('updateLoanUnderwriting', [
                loanUnderwritingId: uw2.loanUnderwritingId, creditScore: 100L, userLogin: userLogin])
        assert ServiceUtil.isError(changeApproved)
        setStatus(appId, 'LEASEAPP_APPROVED', userLogin)

        // Lease underwritings are not visible as loan underwritings.
        // (Lease and loan application ids come from different sequences and may coincide,
        // so the loan-side check must use the application type.)
        assert from('LoanUnderwriting').where('applicationTypeId', 'LOAN', 'applicationId', appId)
                .queryList().every { it.loanUnderwritingId != uw2.loanUnderwritingId }
        assert approvedUw.loanApplicationId == null
        Map unknownApp = dispatcher.runSync('createLeaseUnderwriting', [
                leaseApplicationId: 'NO_SUCH_LEASEAPP', userLogin: userLogin])
        assert ServiceUtil.isError(unknownApp)
    }
}
