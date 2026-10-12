/*******************************************************************************
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
 *******************************************************************************/

package org.apache.ofbiz.finance.lease;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.finance.common.DelinquencyComputation;
import org.apache.ofbiz.finance.common.FinanceContractAdapter;
import org.apache.ofbiz.finance.common.FinanceContractHelper;

/**
 * Lease-layer implementation of {@link FinanceContractAdapter} (STEP L2).
 *
 * <p>Registered from STEP L2 so the common customer summary and contract
 * navigation resolve lease agreements. Lease payments are billed from STEP L4;
 * until the delinquency computation is implemented (STEP L6) this adapter
 * reports no delinquency and no open invoices.
 */
public final class LeaseContractAdapter implements FinanceContractAdapter {

    @Override
    public String getContractTypeId() {
        return FinanceContractHelper.TYPE_LEASE;
    }

    @Override
    public String getAgreementIdField() {
        return "leaseAgreementId";
    }

    @Override
    public String getAgreementEntityName() {
        return "LeaseAgreement";
    }

    @Override
    public String getDescriptionPrefix() {
        return "Lease";
    }

    @Override
    public String findContractIdByAgreementId(Delegator delegator, String agreementId)
            throws GenericEntityException {
        GenericValue agreement = EntityQuery.use(delegator).from("LeaseAgreement")
                .where("leaseAgreementId", agreementId).queryOne();
        return agreement == null ? null : agreement.getString("financeContractId");
    }

    @Override
    public GenericValue findAgreement(Delegator delegator, String financeContractId)
            throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseAgreement")
                .where("financeContractId", financeContractId).cache(false).queryFirst();
    }

    @Override
    public boolean isActive(GenericValue agreement) {
        return "LEASEAGR_ACTIVE".equals(agreement.getString("statusId"));
    }

    @Override
    public String getOrganizationPartyId(Delegator delegator, GenericValue agreement) {
        return agreement.getString("organizationPartyId");
    }

    @Override
    public DelinquencyComputation computeDelinquency(Delegator delegator, GenericValue agreement,
            Timestamp asOfDate) {
        // STEP L6: derive from the confirmed lease payment schedule and lease invoices.
        return new DelinquencyComputation(0L, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Override
    public List<String> findOpenInvoiceIds(Delegator delegator, GenericValue agreement) {
        // STEP L6: lease invoices are created from STEP L4.
        return Collections.emptyList();
    }

    @Override
    public Map<String, Object> getAgreementKeyFields(GenericValue agreement) {
        // Delinquency records carry financeContractId only; there is no lease key column.
        return UtilMisc.toMap();
    }
}
