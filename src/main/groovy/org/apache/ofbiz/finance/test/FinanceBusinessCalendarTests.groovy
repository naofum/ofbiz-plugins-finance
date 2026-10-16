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
import java.sql.Timestamp
import java.time.LocalDate
import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.common.BusinessDayCalculator
import org.apache.ofbiz.finance.common.FinanceBusinessCalendar
import org.apache.ofbiz.finance.common.FinanceInterestCalculator
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L15b: business-day calendars (core TechDataCalendar) for the due-date adjustment
 * (SPEC_COMMON.md 12).
 *
 * Seed calendar FIN_JP (Japanese bank holidays 2026-2030); test calendars TEST_FIN_US,
 * TEST_FIN_AE (Friday/Saturday weekend) and TEST_FIN_X (exceptions, no week definition).
 */
class FinanceBusinessCalendarTests extends OFBizTestCase {

    private static final BigDecimal COST = new BigDecimal('1000000.00')

    FinanceBusinessCalendarTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private static Timestamp day(int year, int month, int dayOfMonth) {
        return UtilDateTime.toTimestamp(month, dayOfMonth, year, 0, 0, 0)
    }

    private static LocalDate ld(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth)
    }

    private static LocalDate toLocal(Timestamp ts) {
        return ts.toLocalDateTime().toLocalDate()
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private FinanceBusinessCalendar cal(String... ids) {
        return FinanceBusinessCalendar.load(delegator, ids as List)
    }

    // Week definition, holidays, working-day exceptions, several calendars, default and fallback.
    void testCalendarDefinition() {
        FinanceBusinessCalendar jp = cal('FIN_JP')
        assert jp.calendarIds == ['FIN_JP']
        assert !jp.isBusinessDay(ld(2026, 5, 4))                  // Greenery Day
        assert !jp.isBusinessDay(ld(2026, 5, 6))                  // substitute holiday
        assert jp.isBusinessDay(ld(2026, 5, 7))
        assert !jp.isBusinessDay(ld(2026, 9, 22))                 // citizens' holiday
        assert !jp.isBusinessDay(ld(2026, 12, 31))                // year-end bank holiday
        assert !jp.isBusinessDay(ld(2027, 1, 2))                  // Saturday (week definition)
        assert jp.isBusinessDay(ld(2027, 1, 4))
        assert !jp.isBusinessDay(ld(2030, 11, 4))                 // substitute holiday, last seeded year

        // Before STEP L15b: Saturday/Sunday only.
        FinanceBusinessCalendar weekend = FinanceBusinessCalendar.WEEKEND_ONLY
        assert weekend.isBusinessDay(ld(2026, 5, 4))
        assert !weekend.isBusinessDay(ld(2026, 5, 9))
        assert BusinessDayCalculator.isBusinessDay(ld(2026, 5, 4))
        assert BusinessDayCalculator.following(ld(2026, 5, 3)) == ld(2026, 5, 4)

        // Week definition with a Friday/Saturday weekend.
        FinanceBusinessCalendar ae = cal('TEST_FIN_AE')
        assert !ae.isBusinessDay(ld(2027, 5, 7))                  // Friday
        assert !ae.isBusinessDay(ld(2027, 5, 8))                  // Saturday
        assert ae.isBusinessDay(ld(2027, 5, 9))                   // Sunday

        // No week definition = Saturday/Sunday closed; exceptions override the week.
        FinanceBusinessCalendar x = cal('TEST_FIN_X')
        assert x.isBusinessDay(ld(2027, 5, 8))                    // working Saturday
        assert !x.isBusinessDay(ld(2027, 5, 9))                   // Sunday
        assert !x.isBusinessDay(ld(2027, 5, 12))                  // holiday on a Wednesday

        // Several calendars: closed when any calendar is closed.
        FinanceBusinessCalendar jpUs = cal('FIN_JP', 'TEST_FIN_US')
        assert !jpUs.isBusinessDay(ld(2027, 7, 5))                // US holiday
        assert !jpUs.isBusinessDay(ld(2027, 7, 19))               // JP holiday
        assert jpUs.isBusinessDay(ld(2027, 7, 6))
        assert !cal('TEST_FIN_X', 'FIN_JP').isBusinessDay(ld(2027, 5, 8))   // working only in one

        // Conventions with holidays.
        assert BusinessDayCalculator.adjust(ld(2026, 5, 3), 'DUEDATE_FOLLOWING', jp) == ld(2026, 5, 7)
        assert BusinessDayCalculator.adjust(ld(2026, 5, 3), 'DUEDATE_PRECEDING', jp) == ld(2026, 5, 1)
        assert BusinessDayCalculator.adjust(ld(2026, 5, 3), 'DUEDATE_NONE', jp) == ld(2026, 5, 3)
        assert BusinessDayCalculator.adjust(ld(2026, 12, 31), 'DUEDATE_MOD_FOLLOW', jp) == ld(2026, 12, 30)
        assert BusinessDayCalculator.adjust(ld(2027, 1, 1), 'DUEDATE_MOD_FOLLOW', jp) == ld(2027, 1, 4)
        assert BusinessDayCalculator.adjust(ld(2027, 7, 3), 'DUEDATE_FOLLOWING', jpUs) == ld(2027, 7, 6)
        Timestamp ts = Timestamp.valueOf('2027-07-05 10:30:00')
        assert BusinessDayCalculator.adjust(ts, 'DUEDATE_FOLLOWING', jpUs) == Timestamp.valueOf('2027-07-06 10:30:00')

        // Unknown calendars are ignored; nothing usable = Saturday/Sunday only.
        assert cal('NO_SUCH_CALENDAR').is(FinanceBusinessCalendar.WEEKEND_ONLY)
        assert cal('NO_SUCH_CALENDAR', 'FIN_JP').calendarIds == ['FIN_JP']

        // Product without calendars: the default (business.calendar.ids = FIN_JP).
        assert FinanceBusinessCalendar.defaultCalendarIds(delegator) == ['FIN_JP']
        assert FinanceBusinessCalendar.forProduct(delegator, 'TEST_LEASEPROD_USD').calendarIds == ['FIN_JP']
    }

    // Lease: quote and acceptance use the product's calendars; existing schedules stay.
    void testLeaseScheduleUsesProductCalendars() {
        GenericValue userLogin = admin()
        Map product = dispatcher.runSync('createFinanceProduct', [productName: 'Test Calendar Lease USD',
                financialProductTypeId: 'LEASE_FIN_NONTRANS', currencyUomId: 'USD', minTermMonths: 12L, maxTermMonths: 72L,
                dueDateConventionEnumId: 'DUEDATE_FOLLOWING', userLogin: userLogin])
        ok(product)
        String productId = product.productId
        ok(dispatcher.runSync('createFinancialProductLease', [productId: productId,
                defaultCalcInterestRate: new BigDecimal('0.030000'), userLogin: userLogin]))
        ['FIN_JP', 'TEST_FIN_US'].each { String c ->
            ok(dispatcher.runSync('addFinancialProductCalendar', [productId: productId, calendarId: c, userLogin: userLogin]))
        }
        FinanceBusinessCalendar calendar = FinanceBusinessCalendar.forProduct(delegator, productId)
        assert calendar.calendarIds == ['FIN_JP', 'TEST_FIN_US']

        String lessee = 'TEST_LSCAL_' + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: lessee, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: lessee, groupName: 'Calendar test lessee'])
        Map app = dispatcher.runSync('createLeaseApplication', [productId: productId, applicantPartyId: lessee,
                requestedTermMonths: 60L, userLogin: userLogin])
        ok(app)
        String appId = app.leaseApplicationId
        ok(dispatcher.runSync('createLeaseApplicationAsset', [leaseApplicationId: appId, fixedAssetTypeId: 'EQUIPMENT',
                assetName: 'Calendar machine', quantity: BigDecimal.ONE, estimatedAcquisitionCost: COST,
                supplierPartyId: 'TEST_LS_SUPPLIER1', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_SUBMITTED', userLogin: userLogin]))
        Map uw = dispatcher.runSync('createLeaseUnderwriting', [leaseApplicationId: appId, creditScore: 720L,
                approvedPrincipalAmount: COST, userLogin: userLogin])
        ok(uw)
        ok(dispatcher.runSync('setLoanUnderwritingStatus', [loanUnderwritingId: uw.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: appId, statusId: 'LEASEAPP_APPROVED', userLogin: userLogin]))

        // Quote from 2027-07-05 (US Independence Day observed), payments in advance.
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: appId, leaseStartDate: day(2027, 7, 5),
                userLogin: userLogin])
        ok(quote)
        List<GenericValue> quoteRows = from('LeasePaymentSchedule').where('leaseQuoteId', quote.leaseQuoteId)
                .orderBy('installmentNumber').queryList()
        assert quoteRows.size() == 60
        assert quoteRows[0].periodFromDate == day(2027, 7, 5)       // periods stay on calendar dates
        assert quoteRows[0].dueDate == day(2027, 7, 6)              // US holiday -> next business day
        assert quoteRows[2].dueDate == day(2027, 9, 7)              // Sunday, then US Labor Day
        assert quoteRows[5].dueDate == day(2027, 12, 6)             // Sunday
        quoteRows.each { GenericValue r -> assert calendar.isBusinessDay(toLocal(r.dueDate)) }

        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote.leaseQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quote.leaseQuoteId,
                agreementDate: day(2027, 3, 15), userLogin: userLogin])
        ok(agreement)
        String agreementId = agreement.leaseAgreementId
        List<GenericValue> rev1 = from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', 1L).orderBy('installmentNumber').queryList()
        (0..<60).each { int i -> assert rev1[i].dueDate == quoteRows[i].dueDate }

        // Acceptance on 2027-09-20 (JP Respect for the Aged Day): revision 2 is re-dated with the calendars.
        ok(dispatcher.runSync('recordLeaseAssetPurchase', [leaseAgreementId: agreementId, assetSeqId: '00001',
                purchaseDate: day(2027, 4, 20), supplierInvoiceRef: 'SUP-INV-CAL', userLogin: userLogin]))
        Map accepted = dispatcher.runSync('acceptLeaseAgreement', [leaseAgreementId: agreementId,
                acceptanceDate: day(2027, 9, 20), userLogin: userLogin])
        ok(accepted)
        List<GenericValue> rev2 = from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', 2L).orderBy('installmentNumber').queryList()
        assert rev2[0].periodFromDate == day(2027, 9, 20)
        assert rev2[0].dueDate == day(2027, 9, 21)
        rev2.each { GenericValue r -> assert calendar.isBusinessDay(toLocal(r.dueDate)) }

        // Removing a calendar changes only later schedules: the stored revisions stay.
        ok(dispatcher.runSync('removeFinancialProductCalendar', [productId: productId, calendarId: 'TEST_FIN_US',
                userLogin: userLogin]))
        assert FinanceBusinessCalendar.forProduct(delegator, productId).calendarIds == ['FIN_JP']
        List<GenericValue> rev1After = from('LeaseAgreementPaymentSchedule').where('leaseAgreementId', agreementId,
                'scheduleRevisionNumber', 1L).orderBy('installmentNumber').queryList()
        assert rev1After[0].dueDate == day(2027, 7, 6)
        assert from('LeasePaymentSchedule').where('leaseQuoteId', quote.leaseQuoteId, 'installmentNumber', 1L)
                .queryOne().dueDate == day(2027, 7, 6)
    }

    // Loan: the quote's due dates use the product's calendars; the interest period stays on calendar dates.
    void testLoanQuoteUsesProductCalendars() {
        GenericValue userLogin = admin()
        Map product = dispatcher.runSync('createFinanceProduct', [productName: 'Test Calendar Loan USD',
                financialProductTypeId: 'PERSONAL_UNSECURED', currencyUomId: 'USD',
                minPrincipalAmount: new BigDecimal('1000'), maxPrincipalAmount: new BigDecimal('1000000'),
                minTermMonths: 1L, maxTermMonths: 60L, defaultAnnualInterestRate: new BigDecimal('0.050000'),
                dueDateConventionEnumId: 'DUEDATE_MOD_FOLLOW', userLogin: userLogin])
        ok(product)
        String productId = product.productId
        ok(dispatcher.runSync('addFinancialProductCalendar', [productId: productId, calendarId: 'FIN_JP', userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('addFinancialProductCalendar', [productId: productId,
                calendarId: 'NO_SUCH_CALENDAR', userLogin: userLogin]))          // must be a core calendar

        Map app = dispatcher.runSync('createLoanApplication', [productId: productId, applicantPartyId: 'TEST_BORROWER1',
                currencyUomId: 'USD', requestedPrincipalAmount: new BigDecimal('12000.00'), requestedTermMonths: 12L,
                userLogin: userLogin])
        ok(app)
        Map quote = dispatcher.runSync('createLoanQuote', [loanApplicationId: app.loanApplicationId,
                firstPaymentDate: day(2027, 1, 1), userLogin: userLogin])
        ok(quote)
        List<GenericValue> rows = from('LoanRepaymentSchedule').where('loanQuoteId', quote.loanQuoteId)
                .orderBy('installmentNumber').queryList()
        assert rows.size() == 12
        assert rows[0].dueDate == day(2027, 1, 4)                   // New Year bank holidays + weekend
        assert rows[0].interestThruDate == day(2027, 1, 1)          // calendar date (STEP 9)
        assert rows[4].dueDate == day(2027, 5, 6)                   // Sat, Sun, May 3-5 holidays
        assert rows[4].interestThruDate == day(2027, 5, 1)
        assert rows[11].dueDate == day(2027, 12, 1)                 // business day: unchanged

        // The loan agreement resolves the same calendars (used when the schedule is rebuilt).
        GenericValue agreement = delegator.makeValue('LoanAgreement', [loanApplicationId: app.loanApplicationId])
        assert FinanceInterestCalculator.resolveBusinessCalendar(delegator, agreement).calendarIds == ['FIN_JP']
    }
}
