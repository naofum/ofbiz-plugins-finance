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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Common parts of the reversal of a mistaken operation (取消, STEP L15a). The
 * layer services decide whether an operation can be reversed (nothing processed
 * after it) and undo it; this helper keeps the {@code FinanceReversal} record,
 * the snapshot of removed rows and the "anything after" checks. Permission is
 * {@code financeAdminPermission} (FINANCE_ADMIN) on every reversal service.
 */
public final class FinanceReversalHelper {

    public static final String TYPE_LEASE_ACCEPT = "FINREV_LEASE_ACCEPT";
    public static final String TYPE_LEASE_TERM = "FINREV_LEASE_TERM";
    public static final String TYPE_LEASE_MOD = "FINREV_LEASE_MOD";
    public static final String TYPE_LOAN_ACTIVATE = "FINREV_LOAN_ACTIVATE";
    public static final String TYPE_LOAN_PREPAY = "FINREV_LOAN_PREPAY";
    public static final String TYPE_LOAN_MOD = "FINREV_LOAN_MOD";

    private FinanceReversalHelper() {
    }

    /** Snapshot text of a value (sorted key=value lines, nulls omitted). */
    public static String snapshot(GenericValue value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.getEntityName()).append('\n');
        Map<String, Object> fields = new TreeMap<>(value.getAllFields());
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (e.getValue() != null && !e.getKey().endsWith("Stamp")) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Record a reversal. */
    public static String record(Delegator delegator, String financeContractId, String typeEnumId, String referenceId,
            Timestamp operationDate, String reason, List<String> reversingAcctgTransIds, String snapshot,
            GenericValue userLogin) throws GenericEntityException {
        String id = delegator.getNextSeqId("FinanceReversal");
        GenericValue r = delegator.makeValue("FinanceReversal");
        r.set("financeReversalId", id);
        r.set("financeContractId", financeContractId);
        r.set("reversalTypeEnumId", typeEnumId);
        r.set("referenceId", referenceId);
        r.set("operationDate", operationDate);
        r.set("reason", reason);
        r.set("reversalDate", UtilDateTime.nowTimestamp());
        r.set("reversedByUserLogin", userLogin == null ? null : userLogin.getString("userLoginId"));
        List<String> ids = new ArrayList<>();
        if (reversingAcctgTransIds != null) {
            for (String t : reversingAcctgTransIds) {
                if (UtilValidate.isNotEmpty(t)) {
                    ids.add(t);
                }
            }
        }
        r.set("reversingAcctgTransIds", ids.isEmpty() ? null : String.join(",", ids));
        r.set("snapshot", snapshot);
        r.set("createdDate", UtilDateTime.nowTimestamp());
        r.create();
        return id;
    }

    /** True when the reason is missing (blank). */
    public static boolean reasonMissing(Object reason) {
        return reason == null || reason.toString().trim().isEmpty();
    }

    /** Rows of an entity matching the conditions created after a timestamp (createdStamp). */
    public static long countCreatedAfter(Delegator delegator, String entityName, Timestamp after, Object... fieldValues)
            throws GenericEntityException {
        List<EntityCondition> conditions = new ArrayList<>();
        for (int i = 0; i + 1 < fieldValues.length; i += 2) {
            conditions.add(EntityCondition.makeCondition((String) fieldValues[i], fieldValues[i + 1]));
        }
        if (after != null) {
            conditions.add(EntityCondition.makeCondition("createdStamp", EntityOperator.GREATER_THAN, after));
        }
        return EntityQuery.use(delegator).from(entityName)
                .where(EntityCondition.makeCondition(conditions, EntityOperator.AND)).cache(false).queryCount();
    }
}
