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

// Lease layer (STEP L2): lease quotes (calculation + quote payment schedule),
// quote status, lease agreements (snapshot of the accepted quote) and agreement status.

import java.sql.Timestamp
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.entity.util.EntityUtilProperties
import org.apache.ofbiz.finance.common.BusinessDayCalculator
import org.apache.ofbiz.finance.common.FinanceContractHelper
import org.apache.ofbiz.finance.common.FinanceInterestCalculator
import org.apache.ofbiz.finance.lease.LeaseCalculator
import org.apache.ofbiz.finance.lease.LeaseProductHelper
import org.apache.ofbiz.service.ServiceUtil

String label(String key, Map args = [:]) {
    return UtilProperties.getMessage('FinanceUiLabels', key, args, parameters.locale)
}

/** Request value with empty strings treated as absent. */
Object param(String name) {
    Object value = parameters.get(name)
    return (value instanceof String && value.isEmpty()) ? null : value
}

Timestamp addMonths(Timestamp ts, int months) {
    return UtilDateTime.adjustTimestamp(ts, Calendar.MONTH, months)
}

Timestamp minusOneDay(Timestamp ts) {
    return UtilDateTime.adjustTimestamp(ts, Calendar.DAY_OF_MONTH, -1)
}

/** Generic validated status change; returns [error:] or [oldStatusId:]. */
Map changeStatus(GenericValue value, String newStatusId) {
    String oldStatusId = value.statusId
    if (oldStatusId == newStatusId) {
        return [oldStatusId: oldStatusId]
    }
    if (from('StatusValidChange').where('statusId', oldStatusId, 'statusIdTo', newStatusId).queryOne() == null) {
        return [error: label('FinanceInvalidStatusChange', [oldStatusId: oldStatusId, statusId: newStatusId])]
    }
    value.statusId = newStatusId
    value.store()
    return [oldStatusId: oldStatusId]
}

// ---------------------------------------------------------------------------
// Quote
// ---------------------------------------------------------------------------

Map createLeaseQuote() {
    String leaseApplicationId = parameters.leaseApplicationId
    GenericValue application = from('LeaseApplication').where('leaseApplicationId', leaseApplicationId).queryOne()
    if (application == null) {
        return error(label('FinanceLeaseApplicationNotFound', [leaseApplicationId: leaseApplicationId]))
    }
    if (!(application.statusId in ['LEASEAPP_RECEIVED', 'LEASEAPP_SUBMITTED', 'LEASEAPP_APPROVED'])) {
        return error(label('FinanceLeaseQuoteApplicationClosed', [leaseApplicationId: leaseApplicationId,
                                                                   statusId: application.statusId]))
    }
    GenericValue terms = LeaseProductHelper.findLeaseProduct(delegator, application.productId)
    GenericValue financialProduct = from('FinancialProduct').where('productId', application.productId).queryOne()
    if (terms == null || financialProduct == null) {
        return error(label('FinanceLeaseProductRequired', [productId: application.productId]))
    }
    List<GenericValue> assets = from('LeaseApplicationAsset').where('leaseApplicationId', leaseApplicationId)
            .orderBy('assetSeqId').queryList()
    if (assets.isEmpty()) {
        return error(label('FinanceLeaseAssetsRequired', [leaseApplicationId: leaseApplicationId]))
    }

    long term = (param('termMonths') ?: application.requestedTermMonths) as long
    Long min = financialProduct.getLong('minTermMonths')
    Long max = financialProduct.getLong('maxTermMonths')
    if (term <= 0 || (min != null && term < min) || (max != null && term > max)) {
        return error(label('FinanceLeaseTermOutOfRange', [termMonths: term, min: min ?: '', max: max ?: '']))
    }

    String timing = param('paymentTimingEnumId') ?: terms.paymentTimingEnumId ?: LeaseProductHelper.TIMING_ADVANCE
    GenericValue timingEnum = from('Enumeration').where('enumId', timing).cache().queryOne()
    if (timingEnum == null || timingEnum.enumTypeId != 'LEASE_PAY_TIMING') {
        return error(label('FinanceLeaseEnumInvalid', [field: 'paymentTimingEnumId', value: timing]))
    }

    BigDecimal factor = param('monthlyRateFactor') as BigDecimal
    BigDecimal calcRate = param('calcInterestRate') as BigDecimal
    if (factor != null && calcRate != null) {
        return error(label('FinanceLeaseQuoteMethodAmbiguous'))
    }
    if (factor == null && calcRate == null) {
        factor = terms.getBigDecimal('defaultMonthlyRateFactor')
        if (factor == null) {
            calcRate = terms.getBigDecimal('defaultCalcInterestRate')
        }
    }
    if (factor == null && calcRate == null) {
        return error(label('FinanceLeaseQuoteRateMissing', [productId: application.productId]))
    }

    String currencyUomId = application.currencyUomId ?: financialProduct.currencyUomId
    int scale = FinanceInterestCalculator.resolveScale(currencyUomId, delegator)
    java.math.RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currencyUomId, delegator)

    BigDecimal acquisitionCost = BigDecimal.ZERO
    assets.each { GenericValue a -> acquisitionCost = acquisitionCost.add(a.getBigDecimal('estimatedAcquisitionCost')) }
    acquisitionCost = acquisitionCost.setScale(scale, mode)
    BigDecimal residual = param('residualValue') as BigDecimal
    if (residual == null) {
        BigDecimal rate = terms.getBigDecimal('residualValueRate') ?: BigDecimal.ZERO
        residual = acquisitionCost.multiply(rate).setScale(scale, mode)
    }
    BigDecimal taxRate = BigDecimal.ZERO
    if (terms.taxable != 'N') {
        taxRate = new BigDecimal(EntityUtilProperties.getPropertyValue('finance', 'lease.tax.rate', '0.10', delegator).trim())
    }

    LeaseCalculator.Result calc
    try {
        calc = LeaseCalculator.calculate(new LeaseCalculator.Input()
                .acquisitionCost(acquisitionCost)
                .residualValue(residual)
                .termMonths(term as int)
                .advance(timing == LeaseProductHelper.TIMING_ADVANCE)
                .monthlyRateFactor(factor)
                .annualCalcRate(calcRate)
                .taxRate(taxRate)
                .currencyRounding(scale, mode))
    } catch (IllegalArgumentException e) {
        return error(label('FinanceLeaseCalcInvalid', [reason: e.getMessage()]))
    }

    Timestamp now = UtilDateTime.nowTimestamp()
    Timestamp start = UtilDateTime.getDayStart((Timestamp) (param('leaseStartDate') ?: now))
    Timestamp firstPayment = timing == LeaseProductHelper.TIMING_ADVANCE ? start : addMonths(start, 1)
    String dueDateConvention = FinanceInterestCalculator.resolveDueDateConvention(delegator, application.productId as String)

    String leaseQuoteId = delegator.getNextSeqId('LeaseQuote')
    makeValue('LeaseQuote', [
            leaseQuoteId       : leaseQuoteId,
            leaseApplicationId : leaseApplicationId,
            statusId           : 'LEASEQT_CREATED',
            currencyUomId      : currencyUomId,
            calcMethodEnumId   : calc.calcMethod,
            acquisitionCost    : acquisitionCost,
            residualValue      : residual,
            termMonths         : term,
            paymentTimingEnumId: timing,
            monthlyLeaseAmount : calc.monthlyLeaseAmount,
            monthlyRateFactor  : calc.monthlyRateFactor,
            calcInterestRate   : calc.calcInterestRate,
            totalLeaseAmount   : calc.totalLeaseAmount,
            totalInterestAmount: calc.totalInterestAmount,
            taxRate            : taxRate,
            totalTaxAmount     : calc.totalTaxAmount,
            leaseStartDate     : start,
            firstPaymentDate   : firstPayment,
            createdDate        : now]).create()

    assets.each { GenericValue a ->
        makeValue('LeaseQuoteAsset', [
                leaseQuoteId    : leaseQuoteId,
                assetSeqId      : a.assetSeqId,
                fixedAssetTypeId: a.fixedAssetTypeId,
                assetName       : a.assetName,
                quantity        : a.quantity,
                acquisitionCost : a.estimatedAcquisitionCost,
                supplierPartyId : a.supplierPartyId]).create()
    }

    calc.rows.each { LeaseCalculator.Row row ->
        int k = row.installmentNumber
        Timestamp due = BusinessDayCalculator.adjust(addMonths(firstPayment, k - 1), dueDateConvention)
        makeValue('LeasePaymentSchedule', [
                leaseQuoteId      : leaseQuoteId,
                installmentNumber : (long) k,
                dueDate           : due,
                periodFromDate    : addMonths(start, k - 1),
                periodThruDate    : minusOneDay(addMonths(start, k)),
                leaseAmount       : row.leaseAmount,
                principalPortion  : row.principalPortion,
                interestPortion   : row.interestPortion,
                taxAmount         : row.taxAmount,
                remainingPrincipal: row.remainingPrincipal]).create()
    }
    return success(leaseQuoteId: leaseQuoteId)
}

Map setLeaseQuoteStatus() {
    GenericValue quote = from('LeaseQuote').where('leaseQuoteId', parameters.leaseQuoteId).queryOne()
    if (quote == null) {
        return error(label('FinanceRecordNotFound', [entityName: 'LeaseQuote', id: parameters.leaseQuoteId]))
    }
    // Only one accepted quote per application.
    if (parameters.statusId == 'LEASEQT_ACCEPTED' && quote.statusId != 'LEASEQT_ACCEPTED'
            && from('LeaseQuote').where('leaseApplicationId', quote.leaseApplicationId, 'statusId', 'LEASEQT_ACCEPTED')
                    .queryCount() > 0) {
        return error(label('FinanceLeaseQuoteAlreadyAccepted', [leaseApplicationId: quote.leaseApplicationId]))
    }
    Map changed = changeStatus(quote, parameters.statusId)
    if (changed.error) {
        return error(changed.error)
    }
    return success(oldStatusId: changed.oldStatusId)
}

// ---------------------------------------------------------------------------
// Agreement
// ---------------------------------------------------------------------------

Map createLeaseAgreement() {
    String leaseQuoteId = parameters.leaseQuoteId
    GenericValue quote = from('LeaseQuote').where('leaseQuoteId', leaseQuoteId).queryOne()
    if (quote == null) {
        return error(label('FinanceRecordNotFound', [entityName: 'LeaseQuote', id: leaseQuoteId]))
    }
    if (from('LeaseAgreement').where('leaseQuoteId', leaseQuoteId).queryCount() > 0) {
        return error(label('FinanceLeaseAgreementExists', [leaseQuoteId: leaseQuoteId]))
    }
    GenericValue application = from('LeaseApplication').where('leaseApplicationId', quote.leaseApplicationId).queryOne()

    // The application, quote and underwriting must have completed their own
    // workflows; this service never changes those statuses implicitly.
    if (application?.statusId != 'LEASEAPP_APPROVED') {
        return error(label('FinanceLeaseAgreementRequiresApprovedApplication', [leaseApplicationId: quote.leaseApplicationId]))
    }
    if (quote.statusId != 'LEASEQT_ACCEPTED') {
        return error(label('FinanceLeaseAgreementRequiresAcceptedQuote', [leaseQuoteId: leaseQuoteId]))
    }
    GenericValue underwriting = from('LoanUnderwriting')
            .where('applicationTypeId', 'LEASE', 'applicationId', application.leaseApplicationId, 'statusId', 'LOANUW_APPROVED')
            .orderBy('-reviewDate').queryFirst()
    if (underwriting == null) {
        return error(label('FinanceLeaseAgreementRequiresApprovedUnderwriting', [leaseApplicationId: application.leaseApplicationId]))
    }
    BigDecimal acquisitionCost = quote.getBigDecimal('acquisitionCost')
    BigDecimal approved = underwriting.getBigDecimal('approvedPrincipalAmount')
    if (approved != null && approved.compareTo(acquisitionCost) < 0) {
        return error(label('FinanceLeaseAgreementExceedsApproval', [acquisitionCost: acquisitionCost, approvedAmount: approved]))
    }

    // The quote schedule must be complete: one row per month, principal
    // recoveries summing to acquisition cost - residual, ending at the residual.
    List<GenericValue> schedule = from('LeasePaymentSchedule').where('leaseQuoteId', leaseQuoteId)
            .orderBy('installmentNumber').queryList()
    BigDecimal residual = quote.getBigDecimal('residualValue') ?: BigDecimal.ZERO
    BigDecimal recovered = BigDecimal.ZERO
    schedule.each { GenericValue row -> recovered = recovered.add(row.getBigDecimal('principalPortion') ?: BigDecimal.ZERO) }
    if (schedule.isEmpty() || schedule.size() != (quote.termMonths as int)
            || recovered.compareTo(acquisitionCost.subtract(residual)) != 0
            || schedule[-1].getBigDecimal('remainingPrincipal')?.compareTo(residual) != 0) {
        return error(label('FinanceLeaseAgreementScheduleIncomplete', [leaseQuoteId: leaseQuoteId]))
    }
    List<GenericValue> assets = from('LeaseQuoteAsset').where('leaseQuoteId', leaseQuoteId).orderBy('assetSeqId').queryList()
    if (assets.isEmpty()) {
        return error(label('FinanceLeaseAssetsRequired', [leaseApplicationId: application.leaseApplicationId]))
    }

    String organizationPartyId = param('organizationPartyId') ?: 'Company'
    if (from('Party').where('partyId', organizationPartyId).queryOne() == null) {
        return error(label('FinanceRecordNotFound', [entityName: 'Party', id: organizationPartyId]))
    }

    // Credit limit: lessee exposure across loan and lease (common layer).
    Map credit = dispatcher.runSync('financeValidateCustomerCreditLimit', [
            customerPartyId: application.applicantPartyId,
            currencyUomId  : quote.currencyUomId,
            principalAmount: acquisitionCost,
            userLogin      : parameters.userLogin,
            locale         : parameters.locale])
    if (ServiceUtil.isError(credit)) {
        return error(ServiceUtil.getErrorMessage(credit))
    }

    GenericValue terms = LeaseProductHelper.findLeaseProduct(delegator, application.productId)
    Timestamp agreementDate = (Timestamp) (param('agreementDate') ?: UtilDateTime.nowTimestamp())
    List<String> suppliers = assets*.supplierPartyId.unique()

    String financeContractId = FinanceContractHelper.createContract(delegator, FinanceContractHelper.TYPE_LEASE,
            application.applicantPartyId, application.productId, quote.currencyUomId, acquisitionCost,
            agreementDate, FinanceContractHelper.STATUS_CONTRACTED)
    String leaseAgreementId = delegator.getNextSeqId('LeaseAgreement')
    GenericValue agreement = makeValue('LeaseAgreement', [
            leaseAgreementId   : leaseAgreementId,
            financeContractId  : financeContractId,
            leaseApplicationId : application.leaseApplicationId,
            leaseQuoteId       : leaseQuoteId,
            statusId           : 'LEASEAGR_CONTRACTED',
            organizationPartyId: organizationPartyId,
            lesseePartyId      : application.applicantPartyId,
            supplierPartyId    : suppliers.size() == 1 ? suppliers[0] : null,
            productId          : application.productId,
            leaseClassEnumId   : terms.leaseClassEnumId,
            revenueMethodEnumId: terms.revenueMethodEnumId,
            agreementDate      : agreementDate])
    ['currencyUomId', 'paymentTimingEnumId', 'calcMethodEnumId', 'acquisitionCost', 'residualValue', 'termMonths',
     'monthlyLeaseAmount', 'monthlyRateFactor', 'calcInterestRate', 'totalLeaseAmount', 'totalInterestAmount',
     'taxRate', 'totalTaxAmount', 'firstPaymentDate'].each { String f -> agreement.set(f, quote.get(f)) }
    agreement.create()

    // Snapshot the confirmed lease payment schedule (revision 1) and the assets.
    schedule.each { GenericValue row ->
        GenericValue line = makeValue('LeaseAgreementPaymentSchedule', [
                leaseAgreementId      : leaseAgreementId,
                scheduleRevisionNumber: 1L,
                leaseQuoteId          : leaseQuoteId])
        ['installmentNumber', 'dueDate', 'periodFromDate', 'periodThruDate', 'leaseAmount', 'principalPortion',
         'interestPortion', 'taxAmount', 'remainingPrincipal'].each { String f -> line.set(f, row.get(f)) }
        line.create()
    }
    assets.each { GenericValue a ->
        makeValue('LeaseAgreementAsset', [
                leaseAgreementId: leaseAgreementId,
                assetSeqId      : a.assetSeqId,
                fixedAssetTypeId: a.fixedAssetTypeId,
                assetName       : a.assetName,
                quantity        : a.quantity,
                acquisitionCost : a.acquisitionCost,
                supplierPartyId : a.supplierPartyId]).create()
    }

    Map contracted = dispatcher.runSync('setLeaseApplicationStatus', [
            leaseApplicationId: application.leaseApplicationId, statusId: 'LEASEAPP_CONTRACTED',
            userLogin: parameters.userLogin, locale: parameters.locale])
    if (ServiceUtil.isError(contracted)) {
        return error(ServiceUtil.getErrorMessage(contracted))
    }
    return success(leaseAgreementId: leaseAgreementId, financeContractId: financeContractId)
}

Map setLeaseAgreementStatus() {
    GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', parameters.leaseAgreementId).queryOne()
    if (agreement == null) {
        return error(label('FinanceRecordNotFound', [entityName: 'LeaseAgreement', id: parameters.leaseAgreementId]))
    }
    Map changed = changeStatus(agreement, parameters.statusId)
    if (changed.error) {
        return error(changed.error)
    }
    // Project the lease status onto the common contract ledger.
    Map leaseToCommon = [
            LEASEAGR_CONTRACTED: FinanceContractHelper.STATUS_CONTRACTED,
            LEASEAGR_ACTIVE    : FinanceContractHelper.STATUS_ACTIVE,
            LEASEAGR_COMPLETED : FinanceContractHelper.STATUS_COMPLETED,
            LEASEAGR_CANCELLED : FinanceContractHelper.STATUS_CANCELLED]
    FinanceContractHelper.updateStatus(delegator, agreement.financeContractId,
            (String) leaseToCommon.get(agreement.statusId))
    return success(oldStatusId: changed.oldStatusId)
}
