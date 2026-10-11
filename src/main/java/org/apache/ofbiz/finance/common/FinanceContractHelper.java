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

package org.apache.ofbiz.finance.common;

import java.math.BigDecimal;
import java.sql.Timestamp;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Common contract ledger helper (STEP L0).
 *
 * <p>Every loan agreement and lease agreement owns exactly one {@code FinanceContract}.
 * The layer (loan / lease) creates the contract when its agreement is created and
 * projects its own status onto the common status through {@link #updateStatus}.
 * Accrual, billing and payment processing for one contract are serialized with
 * {@link #lock} (optimistic conditional update of {@code processingLockVersion}).
 */
public final class FinanceContractHelper {

    public static final String TYPE_LOAN = "LOAN";
    public static final String TYPE_LEASE = "LEASE";

    public static final String STATUS_CONTRACTED = "FINCTR_CONTRACTED";
    public static final String STATUS_ACTIVE = "FINCTR_ACTIVE";
    public static final String STATUS_COMPLETED = "FINCTR_COMPLETED";
    public static final String STATUS_CANCELLED = "FINCTR_CANCELLED";

    private static final int LOCK_ATTEMPTS = 5;

    private FinanceContractHelper() {
    }

    /** Create a common contract ledger row and return its id. */
    public static String createContract(Delegator delegator, String contractTypeId,
            String customerPartyId, String productId, String currencyUomId,
            BigDecimal principalAmount, Timestamp contractDate, String statusId)
            throws GenericEntityException {
        String financeContractId = delegator.getNextSeqId("FinanceContract");
        GenericValue contract = delegator.makeValue("FinanceContract");
        contract.set("financeContractId", financeContractId);
        contract.set("contractTypeId", contractTypeId);
        contract.set("customerPartyId", customerPartyId);
        contract.set("productId", productId);
        contract.set("statusId", UtilValidate.isNotEmpty(statusId) ? statusId : STATUS_CONTRACTED);
        contract.set("currencyUomId", currencyUomId);
        contract.set("principalAmount", principalAmount);
        contract.set("contractDate", contractDate);
        contract.set("processingLockVersion", 0L);
        contract.set("createdDate", UtilDateTime.nowTimestamp());
        contract.create();
        return financeContractId;
    }

    /** Project a layer status change onto the common contract status. No-op when unchanged. */
    public static void updateStatus(Delegator delegator, String financeContractId, String statusId)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(financeContractId) || UtilValidate.isEmpty(statusId)) {
            return;
        }
        GenericValue contract = EntityQuery.use(delegator).from("FinanceContract")
                .where("financeContractId", financeContractId).queryOne();
        if (contract == null) {
            throw new GenericEntityException("FinanceContract not found: " + financeContractId);
        }
        if (!statusId.equals(contract.getString("statusId"))) {
            contract.set("statusId", statusId);
            contract.store();
        }
    }

    /**
     * Credit exposure of a customer across all contract types (loan + lease):
     * the sum of {@code principalAmount} of the customer's ACTIVE contracts.
     * Repayments are not netted, matching the STEP 5 credit-limit rule.
     */
    public static BigDecimal sumActivePrincipal(Delegator delegator, String customerPartyId)
            throws GenericEntityException {
        BigDecimal total = BigDecimal.ZERO;
        if (UtilValidate.isEmpty(customerPartyId)) {
            return total;
        }
        for (GenericValue contract : EntityQuery.use(delegator).from("FinanceContract")
                .where("customerPartyId", customerPartyId, "statusId", STATUS_ACTIVE).queryList()) {
            BigDecimal principal = contract.getBigDecimal("principalAmount");
            if (principal != null) {
                total = total.add(principal);
            }
        }
        return total;
    }

    /**
     * Acquire the processing lock of a contract by incrementing
     * {@code processingLockVersion} with a conditional update. Concurrent
     * transactions processing the same contract are serialized by the row lock
     * taken by the update; a lost race is retried a few times.
     *
     * @return the new lock version
     */
    public static long lock(Delegator delegator, String financeContractId) throws GenericEntityException {
        if (UtilValidate.isEmpty(financeContractId)) {
            throw new GenericEntityException("financeContractId is required to acquire the processing lock");
        }
        for (int attempt = 0; attempt < LOCK_ATTEMPTS; attempt++) {
            GenericValue contract = EntityQuery.use(delegator).from("FinanceContract")
                    .where("financeContractId", financeContractId).cache(false).queryOne();
            if (contract == null) {
                throw new GenericEntityException("FinanceContract not found: " + financeContractId);
            }
            Long currentVersion = contract.getLong("processingLockVersion");
            long nextVersion = currentVersion == null ? 1L : currentVersion + 1L;
            EntityCondition condition = EntityCondition.makeCondition(UtilMisc.toList(
                    EntityCondition.makeCondition("financeContractId", EntityOperator.EQUALS, financeContractId),
                    EntityCondition.makeCondition("processingLockVersion", EntityOperator.EQUALS, currentVersion)),
                    EntityOperator.AND);
            int updated = delegator.storeByCondition("FinanceContract",
                    UtilMisc.toMap("processingLockVersion", nextVersion), condition);
            if (updated == 1) {
                return nextVersion;
            }
        }
        throw new GenericEntityException("Could not acquire processing lock for finance contract "
                + financeContractId);
    }
}
