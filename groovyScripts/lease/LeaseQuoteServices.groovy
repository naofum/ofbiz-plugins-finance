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
import org.apache.ofbiz.finance.lease.LeaseComponentCalculator
import org.apache.ofbiz.finance.lease.LeaseComponentSettings
import org.apache.ofbiz.finance.lease.LeaseProductHelper
import org.apache.ofbiz.finance.lease.LeasePublicChargeServices
import org.apache.ofbiz.finance.lease.LeaseTaxHelper
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
    // Consumption tax rate at the expected lease start (STEP L5); fixed again at the actual start (acceptance).
    Timestamp expectedStart = UtilDateTime.getDayStart((Timestamp) (param('leaseStartDate') ?: UtilDateTime.nowTimestamp()))
    BigDecimal taxRate = LeaseTaxHelper.resolveRate(delegator, application.productId as String, expectedStart).rate

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

    // STEP L9: lease payment components (維持管理費用相当額). Level monthly amounts
    // added on top of the lease payment, with a 4-kind consumption-tax classification
    // and a revenue treatment (separate / included). Components do not change the
    // principal / interest math; new quotes only (existing quotes stay unchanged).
    List components = (parameters.components instanceof List) ? (List) parameters.components : []
    int seq = 0
    for (Object entry : components) {
        if (!(entry instanceof Map)) {
            return error(label('FinanceLeaseComponentInvalid', [reason: 'not a map']))
        }
        Map c = (Map) entry
        String componentTypeEnumId = c.get('componentTypeEnumId')
        GenericValue typeEnum = componentTypeEnumId == null ? null
                : from('Enumeration').where('enumId', componentTypeEnumId).cache().queryOne()
        if (typeEnum == null || typeEnum.enumTypeId != 'LEASE_COMPONENT_TYPE') {
            return error(label('FinanceLeaseEnumInvalid', [field: 'componentTypeEnumId', value: componentTypeEnumId]))
        }
        String taxClassEnumId = c.get('taxClassEnumId') ?: LeaseComponentCalculator.TAX_TAXABLE
        GenericValue taxClassEnum = from('Enumeration').where('enumId', taxClassEnumId).cache().queryOne()
        if (taxClassEnum == null || taxClassEnum.enumTypeId != 'LEASE_TAX_CLASS') {
            return error(label('FinanceLeaseEnumInvalid', [field: 'taxClassEnumId', value: taxClassEnumId]))
        }
        String revenueTreatmentEnumId = c.get('revenueTreatmentEnumId') ?: LeaseComponentCalculator.REV_SEPARATE
        GenericValue revEnum = from('Enumeration').where('enumId', revenueTreatmentEnumId).cache().queryOne()
        if (revEnum == null || revEnum.enumTypeId != 'LEASE_CMP_REVENUE') {
            return error(label('FinanceLeaseEnumInvalid', [field: 'revenueTreatmentEnumId', value: revenueTreatmentEnumId]))
        }
        // STEP L11: the service part (保守料) is always separate revenue.
        if (revenueTreatmentEnumId == LeaseComponentCalculator.REV_INCLUDED
                && LeaseComponentSettings.separateRequired(delegator, componentTypeEnumId)) {
            return error(label('FinanceLeaseComponentSeparateRequired', [componentTypeEnumId: componentTypeEnumId]))
        }
        BigDecimal monthlyAmount = (c.get('monthlyAmount') as BigDecimal)
        // STEP L10 / L10b supply: a public charge equivalent component (固定資産税・自動車税・
        // 重量税 相当額, LeasePublicChargeType.componentTypeEnumId) with no amount is estimated
        // for the quote assets subject to the charge type (by asset type), grouped by the
        // special taxable-base ratio. Local charges need the location (municipalityGeoId).
        GenericValue chargeType = LeasePublicChargeServices.chargeTypeForComponent(delegator, componentTypeEnumId)
        if ((monthlyAmount == null || monthlyAmount.signum() == 0) && chargeType != null
                && (c.get('municipalityGeoId') != null
                    || chargeType.jurisdictionLevelEnumId == LeasePublicChargeServices.JUR_FIXED)) {
            Map<BigDecimal, Map> groups = [:]
            assets.each { GenericValue a ->
                Map<String, BigDecimal> subject = LeasePublicChargeServices.chargeTypesForAssetType(delegator, a.fixedAssetTypeId)
                if (subject.containsKey(chargeType.publicChargeTypeId)) {
                    BigDecimal ratio = subject.get(chargeType.publicChargeTypeId).stripTrailingZeros()
                    Map g = groups.get(ratio) ?: [cost: BigDecimal.ZERO, quantity: BigDecimal.ZERO]
                    g.cost = g.cost.add(a.getBigDecimal('estimatedAcquisitionCost') ?: BigDecimal.ZERO)
                    g.quantity = g.quantity.add(a.getBigDecimal('quantity') ?: BigDecimal.ONE)
                    groups.put(ratio, g)
                }
            }
            if (groups.isEmpty()) {
                return error(label('FinanceLeasePublicChargeNotApplicable', [publicChargeTypeId: chargeType.publicChargeTypeId]))
            }
            monthlyAmount = BigDecimal.ZERO
            for (Map.Entry<BigDecimal, Map> g : groups.entrySet()) {
                Map est = dispatcher.runSync('resolveLeasePublicChargeEstimate', [
                        publicChargeTypeId: chargeType.publicChargeTypeId,
                        acquisitionCost   : g.value.cost,
                        quantity          : g.value.quantity,
                        taxableBaseRatio  : g.key,
                        termMonths        : term,
                        municipalityGeoId : c.get('municipalityGeoId'),
                        rateClassCode     : c.get('rateClassCode'),
                        currencyUomId     : currencyUomId,
                        usefulLifeYears   : (c.get('usefulLifeYears') as Long),
                        asOfDate          : start,
                        userLogin         : parameters.userLogin,
                        locale            : parameters.locale])
                if (ServiceUtil.isError(est)) {
                    return error(ServiceUtil.getErrorMessage(est))
                }
                monthlyAmount = monthlyAmount.add((BigDecimal) est.monthlyEstimate)
            }
        }
        if (monthlyAmount == null || monthlyAmount.signum() < 0) {
            return error(label('FinanceLeaseComponentInvalid', [reason: "monthlyAmount ${monthlyAmount}"]))
        }
        LeaseComponentCalculator.Result alloc
        try {
            alloc = LeaseComponentCalculator.allocate(new LeaseComponentCalculator.Component(
                    componentTypeEnumId, monthlyAmount, taxClassEnumId, revenueTreatmentEnumId),
                    term as int, taxRate, scale, mode)
        } catch (IllegalArgumentException e) {
            return error(label('FinanceLeaseComponentInvalid', [reason: e.getMessage()]))
        }
        seq++
        String componentSeqId = String.format('%05d', seq)
        makeValue('LeaseQuoteComponent', [
                leaseQuoteId          : leaseQuoteId,
                componentSeqId        : componentSeqId,
                componentTypeEnumId   : componentTypeEnumId,
                description           : c.get('description'),
                monthlyAmount         : monthlyAmount.setScale(scale, mode),
                taxClassEnumId        : taxClassEnumId,
                revenueTreatmentEnumId: revenueTreatmentEnumId,
                totalAmount           : alloc.totalAmount,
                totalTaxAmount        : alloc.totalTaxAmount]).create()
        alloc.rows.each { LeaseComponentCalculator.Row r ->
            makeValue('LeasePaymentScheduleComp', [
                    leaseQuoteId          : leaseQuoteId,
                    installmentNumber     : (long) r.installmentNumber,
                    componentSeqId        : componentSeqId,
                    componentTypeEnumId   : componentTypeEnumId,
                    amount                : r.amount,
                    taxAmount             : r.taxAmount,
                    taxClassEnumId        : taxClassEnumId,
                    revenueTreatmentEnumId: revenueTreatmentEnumId]).create()
        }
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
            application.applicantPartyId, organizationPartyId, application.productId, quote.currencyUomId, acquisitionCost,
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
                supplierPartyId : a.supplierPartyId,
                assetStatusId   : 'LSAST_PENDING']).create()
    }

    // STEP L9: snapshot the payment components and their per-installment amounts (revision 1).
    from('LeaseQuoteComponent').where('leaseQuoteId', leaseQuoteId).orderBy('componentSeqId').queryList()
            .each { GenericValue qc ->
        GenericValue ac = makeValue('LeaseAgreementComponent', [leaseAgreementId: leaseAgreementId])
        ['componentSeqId', 'componentTypeEnumId', 'description', 'monthlyAmount', 'taxClassEnumId',
         'revenueTreatmentEnumId', 'totalAmount', 'totalTaxAmount'].each { String f -> ac.set(f, qc.get(f)) }
        ac.create()
    }
    from('LeasePaymentScheduleComp').where('leaseQuoteId', leaseQuoteId)
            .orderBy('installmentNumber', 'componentSeqId').queryList().each { GenericValue qsc ->
        GenericValue asc = makeValue('LeaseAgrPaySchedComp', [
                leaseAgreementId      : leaseAgreementId,
                scheduleRevisionNumber: 1L])
        ['installmentNumber', 'componentSeqId', 'componentTypeEnumId', 'amount', 'taxAmount', 'taxClassEnumId',
         'revenueTreatmentEnumId'].each { String f -> asc.set(f, qsc.get(f)) }
        asc.create()
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
    // The lease start is performed only by the acceptance service (STEP L3).
    if (parameters.statusId == 'LEASEAGR_ACTIVE' && agreement.statusId != 'LEASEAGR_ACTIVE') {
        return error(label('FinanceLeaseStartRequiresAcceptance', [leaseAgreementId: agreement.leaseAgreementId]))
    }
    // The completion is performed only by the expiry services (return / ownership transfer, STEP L7).
    if (parameters.statusId == 'LEASEAGR_COMPLETED' && agreement.statusId != 'LEASEAGR_COMPLETED') {
        return error(label('FinanceLeaseCompleteRequiresExpiry', [leaseAgreementId: agreement.leaseAgreementId]))
    }
    // The early termination is performed only by terminateLeaseAgreement (STEP L14).
    if (parameters.statusId == 'LEASEAGR_TERMINATED' && agreement.statusId != 'LEASEAGR_TERMINATED') {
        return error(label('FinanceLeaseTerminationRequiresService', [leaseAgreementId: agreement.leaseAgreementId]))
    }
    // A purchased asset cannot be left behind by a cancellation (disposal is out of scope).
    if (parameters.statusId == 'LEASEAGR_CANCELLED'
            && from('LeaseAssetPurchase').where('leaseAgreementId', agreement.leaseAgreementId).queryCount() > 0) {
        return error(label('FinanceLeaseCancelAfterPurchase', [leaseAgreementId: agreement.leaseAgreementId]))
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
