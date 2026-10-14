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
import org.apache.ofbiz.finance.common.FinanceContractHelper
import org.apache.ofbiz.finance.common.FinanceContractPartyHelper
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

/**
 * STEP L10a: parties of a finance contract (role x share, effective-dated) and the
 * contract hierarchy. The foundation is additive: existing processing still reads
 * the agreement party columns.
 */
class FinanceContractPartyTests extends OFBizTestCase {

    FinanceContractPartyTests(String name) {
        super(name)
    }

    private GenericValue admin() {
        return from('UserLogin').where('userLoginId', 'system').queryOne()
    }

    private static Timestamp ts(LocalDate d) {
        return Timestamp.valueOf(d.atStartOfDay())
    }

    private void ok(Map result) {
        assert ServiceUtil.isSuccess(result), ServiceUtil.getErrorMessage(result)
    }

    private String newParty(String prefix) {
        String partyId = prefix + delegator.getNextSeqId('Party')
        delegator.create('Party', [partyId: partyId, partyTypeId: 'PARTY_GROUP'])
        delegator.create('PartyGroup', [partyId: partyId, groupName: 'Contract party test ' + partyId])
        return partyId
    }

    private String newContract(String type, String customer, String organization) {
        return FinanceContractHelper.createContract(delegator, type, customer, organization, null, 'USD',
                new BigDecimal('1000.00'), UtilDateTime.nowTimestamp(), FinanceContractHelper.STATUS_CONTRACTED)
    }

    private List<GenericValue> parties(String financeContractId) {
        return from('FinanceContractParty').where('financeContractId', financeContractId).orderBy('roleTypeId').queryList()
    }

    // New contracts register the customer and the organization at 100% in the roles of the contract type.
    void testPrimaryPartiesOnCreation() {
        GenericValue userLogin = admin()
        String lessee = newParty('TEST_CTPL_')
        String leaseContract = newContract('LEASE', lessee, 'Company')
        List<GenericValue> rows = parties(leaseContract)
        assert rows.size() == 2
        GenericValue lesseeRow = rows.find { it.roleTypeId == 'FIN_LESSEE' }
        GenericValue lessorRow = rows.find { it.roleTypeId == 'FIN_LESSOR' }
        assert lesseeRow.partyId == lessee && lessorRow.partyId == 'Company'
        assert lesseeRow.shareRate.compareTo(BigDecimal.ONE) == 0 && lessorRow.shareRate.compareTo(BigDecimal.ONE) == 0
        assert lesseeRow.isPrimary == 'Y' && lesseeRow.thruDate == null

        // Old signature (no organization): only the customer, in the loan role.
        String borrower = newParty('TEST_CTPB_')
        String loanContract = FinanceContractHelper.createContract(delegator, 'LOAN', borrower, null, 'USD',
                new BigDecimal('1000.00'), UtilDateTime.nowTimestamp(), FinanceContractHelper.STATUS_CONTRACTED)
        assert parties(loanContract).collect { it.roleTypeId } == ['FIN_BORROWER']

        // The layer callers pass the organization: a loan agreement created in this run has borrower and lender.
        GenericValue loanAgreement = from('LoanAgreement').orderBy('-agreementDate', '-loanAgreementId').queryFirst()
        if (loanAgreement != null && from('FinanceContractParty').where('financeContractId',
                loanAgreement.financeContractId).queryCount() > 0) {
            assert parties(loanAgreement.financeContractId).collect { it.roleTypeId }.containsAll(['FIN_BORROWER', 'FIN_LENDER'])
        }

        // A lease agreement created through the lease services has lessee and lessor (the agreement's parties).
        String agreementId = contractedLease(userLogin)
        GenericValue agreement = from('LeaseAgreement').where('leaseAgreementId', agreementId).queryOne()
        List<GenericValue> agrRows = parties(agreement.financeContractId)
        assert agrRows.find { it.roleTypeId == 'FIN_LESSEE' }.partyId == agreement.lesseePartyId
        assert agrRows.find { it.roleTypeId == 'FIN_LESSOR' }.partyId == agreement.organizationPartyId
        assert FinanceContractPartyHelper.findParties(delegator, agreement.financeContractId, 'FIN_LESSEE', null).size() == 1
    }

    private String contractedLease(GenericValue userLogin) {
        BigDecimal cost = new BigDecimal('100000.00')
        String lessee = newParty('TEST_CTPA_')
        Map app = dispatcher.runSync('createLeaseApplication', [productId: 'TEST_LEASEPROD_USD', applicantPartyId: lessee,
                requestedTermMonths: 60L, userLogin: userLogin])
        ok(app)
        ok(dispatcher.runSync('createLeaseApplicationAsset', [leaseApplicationId: app.leaseApplicationId,
                fixedAssetTypeId: 'EQUIPMENT', assetName: 'Machine', quantity: BigDecimal.ONE,
                estimatedAcquisitionCost: cost, supplierPartyId: 'TEST_LS_SUPPLIER1', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: app.leaseApplicationId,
                statusId: 'LEASEAPP_SUBMITTED', userLogin: userLogin]))
        Map uw = dispatcher.runSync('createLeaseUnderwriting', [leaseApplicationId: app.leaseApplicationId, creditScore: 720L,
                approvedPrincipalAmount: cost, userLogin: userLogin])
        ok(uw)
        ok(dispatcher.runSync('setLoanUnderwritingStatus', [loanUnderwritingId: uw.loanUnderwritingId,
                statusId: 'LOANUW_APPROVED', userLogin: userLogin]))
        ok(dispatcher.runSync('setLeaseApplicationStatus', [leaseApplicationId: app.leaseApplicationId,
                statusId: 'LEASEAPP_APPROVED', userLogin: userLogin]))
        Map quote = dispatcher.runSync('createLeaseQuote', [leaseApplicationId: app.leaseApplicationId, termMonths: 60L,
                userLogin: userLogin])
        ok(quote)
        ['LEASEQT_PRESENTED', 'LEASEQT_ACCEPTED'].each { String s ->
            ok(dispatcher.runSync('setLeaseQuoteStatus', [leaseQuoteId: quote.leaseQuoteId, statusId: s, userLogin: userLogin]))
        }
        Map agreement = dispatcher.runSync('createLeaseAgreement', [leaseQuoteId: quote.leaseQuoteId, userLogin: userLogin])
        ok(agreement)
        return agreement.leaseAgreementId
    }

    // Add / expire: role family, share range, duplicates, share total <= 100%, effective periods.
    void testAddExpireAndShares() {
        GenericValue userLogin = admin()
        String contract = newContract('LEASE', newParty('TEST_CTPL_'), 'Company')
        String a = newParty('TEST_CTPP_')
        String b = newParty('TEST_CTPP_')
        String c = newParty('TEST_CTPP_')
        Timestamp t0 = ts(LocalDate.now().minusDays(10))
        Timestamp t1 = ts(LocalDate.now().minusDays(5))

        ok(dispatcher.runSync('addFinanceContractParty', [financeContractId: contract, partyId: a, roleTypeId: 'FIN_PARTICIPANT',
                shareRate: new BigDecimal('0.6'), fromDate: t0, userLogin: userLogin]))
        ok(dispatcher.runSync('addFinanceContractParty', [financeContractId: contract, partyId: b, roleTypeId: 'FIN_PARTICIPANT',
                shareRate: new BigDecimal('0.4'), fromDate: t0, userLogin: userLogin]))
        // Over 100%, duplicate, non-contract role, share out of range, unknown party.
        assert ServiceUtil.isError(dispatcher.runSync('addFinanceContractParty', [financeContractId: contract, partyId: c,
                roleTypeId: 'FIN_PARTICIPANT', shareRate: new BigDecimal('0.1'), fromDate: t0, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('addFinanceContractParty', [financeContractId: contract, partyId: a,
                roleTypeId: 'FIN_PARTICIPANT', fromDate: t0, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('addFinanceContractParty', [financeContractId: contract, partyId: c,
                roleTypeId: 'CUSTOMER', userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('addFinanceContractParty', [financeContractId: contract, partyId: c,
                roleTypeId: 'FIN_GUARANTOR', shareRate: BigDecimal.ZERO, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('addFinanceContractParty', [financeContractId: contract, partyId: c,
                roleTypeId: 'FIN_GUARANTOR', shareRate: new BigDecimal('1.5'), userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('addFinanceContractParty', [financeContractId: contract,
                partyId: 'NO_SUCH_PARTY_X', roleTypeId: 'FIN_GUARANTOR', userLogin: userLogin]))
        // A role without a share (guarantor) is accepted.
        ok(dispatcher.runSync('addFinanceContractParty', [financeContractId: contract, partyId: c, roleTypeId: 'FIN_GUARANTOR',
                userLogin: userLogin]))
        assert FinanceContractPartyHelper.findParties(delegator, contract, 'FIN_PARTICIPANT', null).size() == 2

        // End A at t1: from t1 its share is free for C.
        assert ServiceUtil.isError(dispatcher.runSync('expireFinanceContractParty', [financeContractId: contract, partyId: a,
                roleTypeId: 'FIN_PARTICIPANT', fromDate: t0, thruDate: ts(LocalDate.now().minusDays(20)), userLogin: userLogin]))
        ok(dispatcher.runSync('expireFinanceContractParty', [financeContractId: contract, partyId: a,
                roleTypeId: 'FIN_PARTICIPANT', fromDate: t0, thruDate: t1, userLogin: userLogin]))
        assert ServiceUtil.isError(dispatcher.runSync('expireFinanceContractParty', [financeContractId: contract, partyId: a,
                roleTypeId: 'FIN_PARTICIPANT', fromDate: t0, userLogin: userLogin]))
        ok(dispatcher.runSync('addFinanceContractParty', [financeContractId: contract, partyId: c, roleTypeId: 'FIN_PARTICIPANT',
                shareRate: new BigDecimal('0.6'), fromDate: t1, userLogin: userLogin]))
        List<GenericValue> now = FinanceContractPartyHelper.findParties(delegator, contract, 'FIN_PARTICIPANT', null)
        assert now.collect { it.partyId }.toSet() == [b, c].toSet()
        List<GenericValue> before = FinanceContractPartyHelper.findParties(delegator, contract, 'FIN_PARTICIPANT',
                ts(LocalDate.now().minusDays(7)))
        assert before.collect { it.partyId }.toSet() == [a, b].toSet()
        // The primary lessee / lessor rows are untouched.
        assert FinanceContractPartyHelper.findParties(delegator, contract, 'FIN_LESSEE', null).size() == 1
    }

    // Parent contract: set / clear, self and cycles rejected, ancestors.
    void testParentAndCycle() {
        GenericValue userLogin = admin()
        String customer = newParty('TEST_CTPL_')
        String master = newContract('LEASE', customer, 'Company')
        String child1 = newContract('LEASE', customer, 'Company')
        String child2 = newContract('LEASE', customer, 'Company')

        ok(dispatcher.runSync('setFinanceContractParent', [financeContractId: child1, parentFinanceContractId: master, userLogin: userLogin]))
        ok(dispatcher.runSync('setFinanceContractParent', [financeContractId: child2, parentFinanceContractId: child1, userLogin: userLogin]))
        assert FinanceContractPartyHelper.ancestors(delegator, child2) == [child1, master]
        assert from('FinanceContract').where('parentFinanceContractId', master).queryCount() == 1

        assert ServiceUtil.isError(dispatcher.runSync('setFinanceContractParent', [financeContractId: master,
                parentFinanceContractId: child2, userLogin: userLogin]))          // cycle
        assert ServiceUtil.isError(dispatcher.runSync('setFinanceContractParent', [financeContractId: master,
                parentFinanceContractId: master, userLogin: userLogin]))          // self
        assert ServiceUtil.isError(dispatcher.runSync('setFinanceContractParent', [financeContractId: master,
                parentFinanceContractId: 'NO_SUCH_CONTRACT', userLogin: userLogin]))

        ok(dispatcher.runSync('setFinanceContractParent', [financeContractId: child1, userLogin: userLogin]))   // clear
        assert from('FinanceContract').where('financeContractId', child1).queryOne().parentFinanceContractId == null
        assert FinanceContractPartyHelper.ancestors(delegator, child2) == [child1]
    }

    // Backfill: contracts created before L10a get their primary parties; re-running creates nothing.
    void testBackfill() {
        GenericValue userLogin = admin()
        String customer = newParty('TEST_CTPL_')
        // A pre-L10a contract: created without parties.
        String legacyId = delegator.getNextSeqId('FinanceContract')
        delegator.create('FinanceContract', [financeContractId: legacyId, contractTypeId: 'LEASE', customerPartyId: customer,
                statusId: FinanceContractHelper.STATUS_CONTRACTED, currencyUomId: 'USD', principalAmount: new BigDecimal('1000.00'),
                contractDate: UtilDateTime.nowTimestamp(), processingLockVersion: 0L, createdDate: UtilDateTime.nowTimestamp()])
        assert parties(legacyId).isEmpty()

        Map first = dispatcher.runSync('backfillFinanceContractParties', [userLogin: userLogin])
        ok(first)
        assert first.createdPartyCount >= 1L
        // No layer agreement: only the customer can be registered.
        assert parties(legacyId).collect { it.roleTypeId } == ['FIN_LESSEE']
        assert parties(legacyId)[0].partyId == customer

        Map second = dispatcher.runSync('backfillFinanceContractParties', [userLogin: userLogin])
        ok(second)
        assert second.createdPartyCount == 0L
        assert parties(legacyId).size() == 1
    }
}
