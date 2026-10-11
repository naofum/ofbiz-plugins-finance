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
import java.math.RoundingMode
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.common.BusinessDayCalculator
import org.apache.ofbiz.finance.common.FinanceInterestCalculator

// Create a LoanQuote and its repayment schedule from a LoanApplication.
// Supports EQUAL_PAYMENT (元利均等) and EQUAL_PRINCIPAL (元金均等).

String loanApplicationId = context.loanApplicationId
GenericValue app = from('LoanApplication').where('loanApplicationId', loanApplicationId).queryOne()
if (app == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceLoanApplicationNotFound',
            [loanApplicationId: loanApplicationId], context.locale))
}

// Resolve inputs, falling back to the application and its financial product.
GenericValue finProduct = from('FinancialProduct').where('productId', app.productId).queryOne()

BigDecimal principal = context.principalAmount ?: app.requestedPrincipalAmount
Long term = context.termMonths ?: (app.requestedTermMonths as Long)
BigDecimal annualRate = context.annualInterestRate ?: (finProduct?.defaultAnnualInterestRate)
String methodEnumId = context.repaymentMethodEnumId ?: 'LOAN_RPM_EQUAL_PAY'
String currencyUomId = app.currencyUomId ?: 'JPY'

if (!['LOAN_RPM_EQUAL_PAY', 'LOAN_RPM_EQUAL_PRIN'].contains(methodEnumId)) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceInvalidRepaymentMethod',
            [repaymentMethodEnumId: methodEnumId], context.locale))
}

if (principal == null || term == null || term <= 0 || annualRate == null) {
    return error(UtilProperties.getMessage('FinanceUiLabels', 'FinanceQuoteMissingInputs', context.locale))
}

int termInt = term.intValue()
int scale = 2
BigDecimal monthlyRate = annualRate.divide(new BigDecimal('12'), 10, RoundingMode.HALF_UP)

java.sql.Timestamp firstPaymentDate = context.firstPaymentDate ?: UtilDateTime.nowTimestamp()

// Resolve due-date business-day adjustment convention for this product.
String dueDateConvId = FinanceInterestCalculator.resolveDueDateConvention(delegator, app.productId as String)

// Build schedule rows.
List schedule = []
BigDecimal totalPayment = BigDecimal.ZERO
BigDecimal totalInterest = BigDecimal.ZERO
BigDecimal monthlyPayment = null

if ('LOAN_RPM_EQUAL_PRIN'.equals(methodEnumId)) {
    // Equal principal: fixed principal each month + interest on remaining balance.
    BigDecimal principalPortion = principal.divide(new BigDecimal(termInt), scale, RoundingMode.HALF_UP)
    BigDecimal balance = principal
    for (int i = 1; i <= termInt; i++) {
        BigDecimal interest = balance.multiply(monthlyRate).setScale(scale, RoundingMode.HALF_UP)
        BigDecimal thisPrincipal = (i == termInt) ? balance : principalPortion
        BigDecimal payment = thisPrincipal.add(interest).setScale(scale, RoundingMode.HALF_UP)
        balance = balance.subtract(thisPrincipal).setScale(scale, RoundingMode.HALF_UP)
        schedule << [n: i, interest: interest, principal: thisPrincipal, payment: payment, balance: balance]
        totalPayment = totalPayment.add(payment)
        totalInterest = totalInterest.add(interest)
    }
} else {
    // Equal payment (元利均等): level payment using annuity formula.
    if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
        monthlyPayment = principal.divide(new BigDecimal(termInt), scale, RoundingMode.HALF_UP)
    } else {
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate)
        BigDecimal pow = onePlusR.pow(termInt)
        monthlyPayment = principal.multiply(monthlyRate).multiply(pow)
                .divide(pow.subtract(BigDecimal.ONE), scale, RoundingMode.HALF_UP)
    }
    BigDecimal balance = principal
    for (int i = 1; i <= termInt; i++) {
        BigDecimal interest = balance.multiply(monthlyRate).setScale(scale, RoundingMode.HALF_UP)
        BigDecimal thisPrincipal
        BigDecimal payment
        if (i == termInt) {
            thisPrincipal = balance
            payment = balance.add(interest).setScale(scale, RoundingMode.HALF_UP)
        } else {
            thisPrincipal = monthlyPayment.subtract(interest).setScale(scale, RoundingMode.HALF_UP)
            payment = monthlyPayment
        }
        balance = balance.subtract(thisPrincipal).setScale(scale, RoundingMode.HALF_UP)
        schedule << [n: i, interest: interest, principal: thisPrincipal, payment: payment, balance: balance]
        totalPayment = totalPayment.add(payment)
        totalInterest = totalInterest.add(interest)
    }
}

// Persist the quote header.
String loanQuoteId = delegator.getNextSeqId('LoanQuote')
GenericValue quote = makeValue('LoanQuote', [
        loanQuoteId          : loanQuoteId,
        loanApplicationId    : loanApplicationId,
        statusId             : 'LOANQT_CREATED',
        currencyUomId        : currencyUomId,
        principalAmount      : principal,
        annualInterestRate   : annualRate,
        termMonths           : (term as BigDecimal),
        repaymentMethodEnumId: methodEnumId,
        monthlyPaymentAmount : monthlyPayment,
        totalPaymentAmount   : totalPayment.setScale(scale, RoundingMode.HALF_UP),
        totalInterestAmount  : totalInterest.setScale(scale, RoundingMode.HALF_UP),
        firstPaymentDate     : firstPaymentDate,
        createdDate          : UtilDateTime.nowTimestamp()])
quote.create()

// Persist schedule rows.
// interestThruDate[n] = firstPaymentDate + (n-1) months  (= dueDate, calendar value)
// interestFromDate[1] = firstPaymentDate - 1 month + 1 day  (provisional; overwritten with
//                       agreementDate+1 when the agreement is created)
// interestFromDate[n] = interestThruDate[n-1] + 1 day  (n >= 2)
java.sql.Timestamp prevThru = null
for (row in schedule) {
    java.sql.Timestamp due = UtilDateTime.adjustTimestamp(firstPaymentDate, java.util.Calendar.MONTH, (row.n - 1) as int)
    // interestThruDate is the calendar due date (before any business-day adjustment).
    java.sql.Timestamp interestThru = due
    // Apply business-day adjustment to dueDate (interestThruDate stays as calendar date).
    due = BusinessDayCalculator.adjust(due, dueDateConvId)
    // interestFromDate: day after the previous installment's thruDate, or for n=1
    // one month before firstPaymentDate + 1 day (provisional estimate).
    java.sql.Timestamp interestFrom
    if (prevThru == null) {
        // n=1: reverse-calculate from firstPaymentDate (provisional; replaced at agreement time)
        java.sql.Timestamp oneMonthBefore = UtilDateTime.adjustTimestamp(firstPaymentDate, java.util.Calendar.MONTH, -1)
        interestFrom = new java.sql.Timestamp(oneMonthBefore.getTime() + 86_400_000L)
    } else {
        interestFrom = new java.sql.Timestamp(prevThru.getTime() + 86_400_000L)
    }
    prevThru = interestThru
    GenericValue line = makeValue('LoanRepaymentSchedule', [
            loanQuoteId      : loanQuoteId,
            installmentNumber: (row.n as BigDecimal),
            dueDate          : due,
            interestFromDate : interestFrom,
            interestThruDate : interestThru,
            paymentAmount    : row.payment,
            principalAmount  : row.principal,
            interestAmount   : row.interest,
            remainingBalance : row.balance])
    line.create()
}

Map result = success()
result.loanQuoteId = loanQuoteId
return result
