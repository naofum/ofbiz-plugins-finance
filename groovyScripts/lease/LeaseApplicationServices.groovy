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

// Lease layer (STEP L1): lease applications, requested assets and the
// lease-side entry points of the common underwriting.

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.lease.LeaseProductHelper
import org.apache.ofbiz.service.ServiceUtil

String label(String key, Map args = [:]) {
    return UtilProperties.getMessage('FinanceUiLabels', key, args, parameters.locale)
}

/** Request value with empty strings treated as absent (zero amounts are kept). */
Object param(String name) {
    Object value = parameters.get(name)
    return (value instanceof String && value.isEmpty()) ? null : value
}

GenericValue findApplication(String leaseApplicationId) {
    return from('LeaseApplication').where('leaseApplicationId', leaseApplicationId).queryOne()
}

/** Error message when the application is missing or no longer editable, else null. */
String checkEditable(GenericValue application, String leaseApplicationId) {
    if (application == null) {
        return label('FinanceLeaseApplicationNotFound', [leaseApplicationId: leaseApplicationId])
    }
    if (application.statusId != 'LEASEAPP_RECEIVED') {
        return label('FinanceLeaseApplicationImmutable', [leaseApplicationId: leaseApplicationId])
    }
    return null
}

/** Validate that the product is a configured lease product and the term is within its range. */
Map validateProductAndTerm(String productId, Object termMonths) {
    GenericValue terms = LeaseProductHelper.findLeaseProduct(delegator, productId)
    GenericValue financialProduct = productId ? from('FinancialProduct').where('productId', productId).queryOne() : null
    if (terms == null || financialProduct == null
            || !LeaseProductHelper.isLeaseProductType(delegator, financialProduct.financialProductTypeId)) {
        return [error: label('FinanceLeaseProductRequired', [productId: productId])]
    }
    if (termMonths != null) {
        long term = termMonths as long
        Long min = financialProduct.getLong('minTermMonths')
        Long max = financialProduct.getLong('maxTermMonths')
        if (term <= 0 || (min != null && term < min) || (max != null && term > max)) {
            return [error: label('FinanceLeaseTermOutOfRange', [termMonths: term, min: min ?: '', max: max ?: ''])]
        }
    }
    return [financialProduct: financialProduct]
}

Map createLeaseApplication() {
    Map checked = validateProductAndTerm(parameters.productId, parameters.requestedTermMonths)
    if (checked.error) {
        return error(checked.error)
    }
    // Applications are created only in the initial RECEIVED state; later status
    // changes must go through setLeaseApplicationStatus.
    String leaseApplicationId = delegator.getNextSeqId('LeaseApplication')
    java.sql.Timestamp now = UtilDateTime.nowTimestamp()
    GenericValue application = makeValue('LeaseApplication', [
            leaseApplicationId : leaseApplicationId,
            productId          : parameters.productId,
            applicantPartyId   : parameters.applicantPartyId,
            statusId           : 'LEASEAPP_RECEIVED',
            currencyUomId      : param('currencyUomId') ?: checked.financialProduct.currencyUomId,
            requestedTermMonths: parameters.requestedTermMonths,
            applicationDate    : parameters.applicationDate ?: now,
            purpose            : param('purpose'),
            comments           : param('comments')])
    application.create()
    makeValue('LeaseApplicationStatus', [
            leaseApplicationId : leaseApplicationId,
            statusId           : 'LEASEAPP_RECEIVED',
            statusDate         : now,
            changeByUserLoginId: parameters.userLogin?.userLoginId]).create()
    return success(leaseApplicationId: leaseApplicationId)
}

Map updateLeaseApplication() {
    GenericValue application = findApplication(parameters.leaseApplicationId)
    String notEditable = checkEditable(application, parameters.leaseApplicationId)
    if (notEditable) {
        return error(notEditable)
    }
    List<String> fields = ['productId', 'applicantPartyId', 'currencyUomId', 'requestedTermMonths',
                           'applicationDate', 'purpose', 'comments']
    fields.each { String f ->
        if (param(f) != null) {
            application.set(f, param(f))
        }
    }
    Map checked = validateProductAndTerm(application.productId, application.requestedTermMonths)
    if (checked.error) {
        return error(checked.error)
    }
    application.store()
    return success()
}

Map deleteLeaseApplication() {
    GenericValue application = findApplication(parameters.leaseApplicationId)
    String notEditable = checkEditable(application, parameters.leaseApplicationId)
    if (notEditable) {
        return error(notEditable)
    }
    if (from('LoanUnderwriting').where('applicationTypeId', 'LEASE', 'applicationId', parameters.leaseApplicationId)
            .queryCount() > 0) {
        return error(label('FinanceLeaseApplicationHasUnderwriting', [leaseApplicationId: parameters.leaseApplicationId]))
    }
    delegator.removeByAnd('LeaseApplicationAsset', [leaseApplicationId: parameters.leaseApplicationId])
    delegator.removeByAnd('LeaseApplicationStatus', [leaseApplicationId: parameters.leaseApplicationId])
    application.remove()
    return success()
}

Map setLeaseApplicationStatus() {
    String leaseApplicationId = parameters.leaseApplicationId
    String newStatusId = parameters.statusId
    GenericValue application = findApplication(leaseApplicationId)
    if (application == null) {
        return error(label('FinanceLeaseApplicationNotFound', [leaseApplicationId: leaseApplicationId]))
    }
    String oldStatusId = application.statusId
    if (oldStatusId == newStatusId) {
        return success(oldStatusId: oldStatusId)
    }
    if (from('StatusValidChange').where('statusId', oldStatusId, 'statusIdTo', newStatusId).queryOne() == null) {
        return error(label('FinanceInvalidStatusChange', [oldStatusId: oldStatusId, statusId: newStatusId]))
    }
    // An application without requested assets cannot be approved.
    if (newStatusId == 'LEASEAPP_APPROVED'
            && from('LeaseApplicationAsset').where('leaseApplicationId', leaseApplicationId).queryCount() == 0) {
        return error(label('FinanceLeaseAssetsRequired', [leaseApplicationId: leaseApplicationId]))
    }
    application.statusId = newStatusId
    application.store()
    makeValue('LeaseApplicationStatus', [
            leaseApplicationId : leaseApplicationId,
            statusId           : newStatusId,
            statusDate         : UtilDateTime.nowTimestamp(),
            changeByUserLoginId: parameters.userLogin?.userLoginId]).create()
    return success(oldStatusId: oldStatusId)
}

/** Validate asset line values; returns an error message or null. */
String validateAsset(GenericValue application, GenericValue asset) {
    if (from('FixedAssetType').where('fixedAssetTypeId', asset.fixedAssetTypeId).cache().queryOne() == null) {
        return label('FinanceRecordNotFound', [entityName: 'FixedAssetType', id: asset.fixedAssetTypeId])
    }
    if (!LeaseProductHelper.isAssetTypeAllowed(delegator, application.productId, asset.fixedAssetTypeId)) {
        return label('FinanceLeaseAssetTypeNotAllowed', [fixedAssetTypeId: asset.fixedAssetTypeId, productId: application.productId])
    }
    BigDecimal quantity = asset.getBigDecimal('quantity')
    if (quantity == null || quantity.signum() <= 0) {
        return label('FinanceLeaseAssetQuantityInvalid')
    }
    BigDecimal cost = asset.getBigDecimal('estimatedAcquisitionCost')
    if (cost == null || cost.signum() <= 0) {
        return label('FinanceLeaseAssetCostInvalid')
    }
    return null
}

List<String> assetFields() {
    return ['fixedAssetTypeId', 'assetName', 'quantity', 'estimatedAcquisitionCost', 'supplierPartyId', 'comments']
}

Map createLeaseApplicationAsset() {
    GenericValue application = findApplication(parameters.leaseApplicationId)
    String notEditable = checkEditable(application, parameters.leaseApplicationId)
    if (notEditable) {
        return error(notEditable)
    }
    GenericValue asset = makeValue('LeaseApplicationAsset', [leaseApplicationId: parameters.leaseApplicationId])
    assetFields().each { String f -> asset.set(f, param(f)) }
    if (asset.quantity == null) {
        asset.quantity = BigDecimal.ONE
    }
    String invalid = validateAsset(application, asset)
    if (invalid) {
        return error(invalid)
    }
    delegator.setNextSubSeqId(asset, 'assetSeqId', 5, 1)
    asset.create()
    return success(leaseApplicationId: asset.leaseApplicationId, assetSeqId: asset.assetSeqId)
}

Map updateLeaseApplicationAsset() {
    GenericValue application = findApplication(parameters.leaseApplicationId)
    String notEditable = checkEditable(application, parameters.leaseApplicationId)
    if (notEditable) {
        return error(notEditable)
    }
    GenericValue asset = from('LeaseApplicationAsset')
            .where('leaseApplicationId', parameters.leaseApplicationId, 'assetSeqId', parameters.assetSeqId).queryOne()
    if (asset == null) {
        return error(label('FinanceRecordNotFound', [entityName: 'LeaseApplicationAsset', id: parameters.assetSeqId]))
    }
    assetFields().each { String f ->
        if (param(f) != null) {
            asset.set(f, param(f))
        }
    }
    String invalid = validateAsset(application, asset)
    if (invalid) {
        return error(invalid)
    }
    asset.store()
    return success()
}

Map removeLeaseApplicationAsset() {
    GenericValue application = findApplication(parameters.leaseApplicationId)
    String notEditable = checkEditable(application, parameters.leaseApplicationId)
    if (notEditable) {
        return error(notEditable)
    }
    GenericValue asset = from('LeaseApplicationAsset')
            .where('leaseApplicationId', parameters.leaseApplicationId, 'assetSeqId', parameters.assetSeqId).queryOne()
    if (asset == null) {
        return error(label('FinanceRecordNotFound', [entityName: 'LeaseApplicationAsset', id: parameters.assetSeqId]))
    }
    asset.remove()
    return success()
}

/** Create the common underwriting for a lease application (applicationTypeId=LEASE). */
Map createLeaseUnderwriting() {
    GenericValue application = findApplication(parameters.leaseApplicationId)
    if (application == null) {
        return error(label('FinanceLeaseApplicationNotFound', [leaseApplicationId: parameters.leaseApplicationId]))
    }
    Map result = dispatcher.runSync('createFinanceUnderwriting', [
            applicationTypeId      : 'LEASE',
            applicationId          : parameters.leaseApplicationId,
            creditScore            : parameters.creditScore,
            approvedPrincipalAmount: parameters.approvedPrincipalAmount,
            reviewerPartyId        : param('reviewerPartyId'),
            comments               : param('comments'),
            userLogin              : parameters.userLogin,
            locale                 : parameters.locale])
    if (ServiceUtil.isError(result)) {
        return error(ServiceUtil.getErrorMessage(result))
    }
    return success(loanUnderwritingId: result.loanUnderwritingId)
}

/** Approval validator called by the common underwriting status service for LEASE. */
Map validateLeaseUnderwritingApproval() {
    GenericValue application = findApplication(parameters.applicationId)
    if (application == null) {
        return error(label('FinanceLeaseApplicationNotFound', [leaseApplicationId: parameters.applicationId]))
    }
    if (from('LeaseApplicationAsset').where('leaseApplicationId', parameters.applicationId).queryCount() == 0) {
        return error(label('FinanceLeaseAssetsRequired', [leaseApplicationId: parameters.applicationId]))
    }
    // STEP L2: the approved amount (= acquisition cost) is required and must fit
    // the lessee's credit line together with the existing loan + lease exposure.
    BigDecimal approved = parameters.principalAmount
    if (approved == null || approved.signum() <= 0) {
        return error(label('FinanceLeaseApprovedAmountRequired'))
    }
    Map credit = dispatcher.runSync('financeValidateCustomerCreditLimit', [
            customerPartyId: application.applicantPartyId,
            currencyUomId  : application.currencyUomId,
            principalAmount: approved,
            userLogin      : parameters.userLogin,
            locale         : parameters.locale])
    if (ServiceUtil.isError(credit)) {
        return error(ServiceUtil.getErrorMessage(credit))
    }
    return success()
}
