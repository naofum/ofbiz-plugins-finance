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

// Screen data for FinanceContractParties (STEP L10a): the contract, its parties
// (all periods, with the role / party names and whether effective now), the parent
// contract and the child contracts.

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.common.FinanceContractPartyHelper
import org.apache.ofbiz.party.party.PartyHelper

GenericValue contract = parameters.financeContractId ? from('FinanceContract')
        .where('financeContractId', parameters.financeContractId).queryOne() : null
context.financeContract = contract
if (contract == null) {
    return
}
context.financeContractId = contract.financeContractId
java.sql.Timestamp now = UtilDateTime.nowTimestamp()

context.contractParties = from('FinanceContractParty').where('financeContractId', contract.financeContractId)
        .orderBy('roleTypeId', 'fromDate', 'partyId').queryList().collect { GenericValue p ->
    Map row = new LinkedHashMap(p.getAllFields())
    row.partyName = PartyHelper.getPartyName(delegator, p.partyId, false)
    row.roleDescription = from('RoleType').where('roleTypeId', p.roleTypeId).cache().queryOne()?.description
    row.effective = FinanceContractPartyHelper.isEffective(p, now)
    row.canExpire = p.thruDate == null
    return row
}
context.roleOptions = from('RoleType').where('parentTypeId', FinanceContractPartyHelper.ROOT_ROLE)
        .orderBy('roleTypeId').cache().queryList()
context.parentContract = contract.parentFinanceContractId ? from('FinanceContract')
        .where('financeContractId', contract.parentFinanceContractId).queryOne() : null
context.childContracts = from('FinanceContract').where('parentFinanceContractId', contract.financeContractId)
        .orderBy('financeContractId').queryList()
