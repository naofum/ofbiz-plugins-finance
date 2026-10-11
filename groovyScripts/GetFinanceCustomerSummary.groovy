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

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.common.FinanceContractAdapter
import org.apache.ofbiz.finance.common.FinanceContractAdapters
import org.apache.ofbiz.finance.common.FinanceContractHelper

// Common layer (STEP L0): customer-level view across all contract types.
// Returns the customer's FinanceContracts (with the layer agreement id resolved
// through the contract-type adapter), the active credit exposure, the latest
// delinquency snapshot per contract and the dunning notices.
String customerPartyId = context.customerPartyId

List<GenericValue> contractValues = from('FinanceContract')
        .where('customerPartyId', customerPartyId)
        .orderBy('-contractDate', 'financeContractId').queryList()

List<Map> contracts = []
List<Map> latestDelinquencies = []
List<String> contractIds = []
for (GenericValue contract : contractValues) {
    String financeContractId = contract.financeContractId
    contractIds << financeContractId
    FinanceContractAdapter adapter = FinanceContractAdapters.forType(contract.contractTypeId)
    GenericValue agreement = adapter?.findAgreement(delegator, financeContractId)
    String agreementId = agreement == null ? null : agreement.getString(adapter.getAgreementIdField())

    Map row = new LinkedHashMap(contract.getAllFields())
    row.agreementId = agreementId
    contracts << row

    GenericValue latest = from('LoanDelinquency').where('financeContractId', financeContractId)
            .orderBy('-asOfDate').queryFirst()
    if (latest != null) {
        Map delinquency = new LinkedHashMap(latest.getAllFields())
        delinquency.contractTypeId = contract.contractTypeId
        delinquency.agreementId = agreementId
        latestDelinquencies << delinquency
    }
}

List<GenericValue> dunningNotices = contractIds.isEmpty() ? [] : from('LoanDunningNotice')
        .where(org.apache.ofbiz.entity.condition.EntityCondition.makeCondition('financeContractId',
                org.apache.ofbiz.entity.condition.EntityOperator.IN, contractIds))
        .orderBy('-noticeDate').queryList()

Map result = success()
result.contracts = contracts
result.activeExposure = FinanceContractHelper.sumActivePrincipal(delegator, customerPartyId)
result.latestDelinquencies = latestDelinquencies
result.dunningNotices = dunningNotices
return result
