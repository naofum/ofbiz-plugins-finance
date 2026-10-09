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

import java.math.BigDecimal
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.GenericValue

// Create a LoanAgreement from an accepted LoanQuote and open a FinAccount (loan account).

String loanQuoteId = context.loanQuoteId
GenericValue quote = from('LoanQuote').where('loanQuoteId', loanQuoteId).queryOne()
if (quote == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceRecordNotFound',
            [entityName: 'LoanQuote', id: loanQuoteId], context.locale))
}

GenericValue existingAgreement = from('LoanAgreement').where('loanQuoteId', loanQuoteId).queryFirst()
if (existingAgreement != null) {
    Map existingResult = success()
    existingResult.loanAgreementId = existingAgreement.loanAgreementId
    existingResult.finAccountId = existingAgreement.finAccountId
    return existingResult
}

GenericValue app = from('LoanApplication').where('loanApplicationId', quote.loanApplicationId).queryOne()
if (app == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanApplicationNotFound',
            [loanApplicationId: quote.loanApplicationId], context.locale))
}

// A contract can only finalize an application, quote and underwriting that have
// completed their respective approval workflows. Never change those statuses
// implicitly here: callers must use the validated transition services first.
if (!'LOANAPP_APPROVED'.equals(app.statusId)) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceAgreementRequiresApprovedApplication',
            [loanApplicationId: app.loanApplicationId], context.locale))
}
if (!'LOANQT_ACCEPTED'.equals(quote.statusId)) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceAgreementRequiresAcceptedQuote',
            [loanQuoteId: loanQuoteId], context.locale))
}
List<GenericValue> approvedUnderwritings = from('LoanUnderwriting')
        .where('loanApplicationId', app.loanApplicationId, 'statusId', 'LOANUW_APPROVED').queryList()
if (approvedUnderwritings.isEmpty()) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceAgreementRequiresApprovedUnderwriting',
            [loanApplicationId: app.loanApplicationId], context.locale))
}
List<GenericValue> sourceSchedule = from('LoanRepaymentSchedule')
        .where('loanQuoteId', loanQuoteId).orderBy('installmentNumber').queryList()
BigDecimal scheduledPrincipal = sourceSchedule.inject(BigDecimal.ZERO) { BigDecimal sum, GenericValue row ->
    sum.add(row.getBigDecimal('principalAmount') ?: BigDecimal.ZERO)
}
BigDecimal finalBalance = sourceSchedule.isEmpty()
        ? null : sourceSchedule[-1].getBigDecimal('remainingBalance')
if (sourceSchedule.isEmpty()
        || sourceSchedule.size() != (quote.termMonths as Integer)
        || scheduledPrincipal.compareTo(quote.getBigDecimal('principalAmount')) != 0
        || finalBalance == null || finalBalance.compareTo(BigDecimal.ZERO) != 0) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceAgreementScheduleMissing',
            [loanQuoteId: loanQuoteId], context.locale))
}

String borrowerPartyId = app.applicantPartyId
String currencyUomId = quote.currencyUomId ?: 'JPY'
java.sql.Timestamp now = UtilDateTime.nowTimestamp()
java.sql.Timestamp agreementDate = context.agreementDate ?: now
String organizationPartyId = context.organizationPartyId ?: 'Company'
GenericValue principalReceivableDefault = from('GlAccountTypeDefault').where(
        'organizationPartyId', organizationPartyId, 'glAccountTypeId', 'ACCOUNTS_RECEIVABLE').queryFirst()
if (principalReceivableDefault == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceGlAccountMappingMissing',
            [organizationPartyId: organizationPartyId, glAccountTypeId: 'ACCOUNTS_RECEIVABLE'], context.locale))
}

// 0) Secured loan collateral: the product's collateral and loan-to-value
// requirements must be satisfied before the contract can be finalized.
try {
    runService('financeValidateCollateralLtv', [
            loanApplicationId: app.loanApplicationId,
            principalAmount  : quote.getBigDecimal('principalAmount'),
            userLogin        : userLogin])
} catch (org.apache.ofbiz.service.ExecutionServiceException e) {
    return error(e.getMessage())
}

// 1) Open the core loan account and retain its principal receivable GL account.
Map finAcctResult = runService('createFinAccount', [
        finAccountTypeId   : 'LOAN_ACCOUNT',
        finAccountName     : 'Loan ' + loanQuoteId,
        statusId           : 'FNACT_ACTIVE',
        currencyUomId      : currencyUomId,
        organizationPartyId: organizationPartyId,
        ownerPartyId       : borrowerPartyId,
        postToGlAccountId  : principalReceivableDefault.glAccountId,
        fromDate           : now,
        userLogin          : userLogin])
String finAccountId = finAcctResult.finAccountId

// 2) Create the draft contract header from the accepted terms.
String loanAgreementId = delegator.getNextSeqId('LoanAgreement')
GenericValue agreement = makeValue('LoanAgreement', [
        loanAgreementId   : loanAgreementId,
        loanApplicationId : quote.loanApplicationId,
        loanQuoteId       : loanQuoteId,
        finAccountId      : finAccountId,
        statusId          : 'LOANAGR_DRAFT',
        borrowerPartyId   : borrowerPartyId,
        currencyUomId     : currencyUomId,
        principalAmount   : quote.principalAmount,
        annualInterestRate: quote.annualInterestRate,
        termMonths        : quote.termMonths,
        agreementDate     : agreementDate,
        firstPaymentDate  : quote.firstPaymentDate,
        processingLockVersion: 0L])
agreement.create()

// 3) Snapshot the accepted quote schedule. Subsequent quote edits cannot alter
// billing terms for this agreement.
for (GenericValue source : sourceSchedule) {
    makeValue('LoanAgreementRepaymentSchedule', [
            loanAgreementId   : loanAgreementId,
            installmentNumber : source.installmentNumber,
            loanQuoteId       : loanQuoteId,
            dueDate           : source.dueDate,
            paymentAmount     : source.paymentAmount,
            principalAmount   : source.principalAmount,
            interestAmount    : source.interestAmount,
            remainingBalance  : source.remainingBalance]).create()
}

// 3b) Link declared collateral to the agreement (lien), assigning each its
// latest appraised value.
runService('financeCreateLoanCollateralLinks', [
        loanAgreementId : loanAgreementId,
        loanApplicationId: app.loanApplicationId,
        fromDate        : agreementDate,
        userLogin       : userLogin])

// 4) Activate the contract and mark the application contracted through the
// validated transition services. Any failure rolls back the whole transaction.
runService('setLoanAgreementStatus', [
        loanAgreementId: loanAgreementId, statusId: 'LOANAGR_ACTIVE', userLogin: userLogin])
runService('setLoanApplicationStatus', [
        loanApplicationId: app.loanApplicationId, statusId: 'LOANAPP_CONTRACTED', userLogin: userLogin])

Map result = success()
result.loanAgreementId = loanAgreementId
result.finAccountId = finAccountId
return result
