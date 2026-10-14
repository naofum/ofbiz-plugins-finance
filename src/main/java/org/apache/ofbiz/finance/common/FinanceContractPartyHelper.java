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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Parties of a finance contract and the contract hierarchy (STEP L10a).
 *
 * <p>{@code FinanceContractParty} records each party of a contract in a role
 * (core {@code RoleType} under {@link #ROOT_ROLE}) with an optional share and an
 * effective period. It is the foundation for syndicated leases, subleases, fleet
 * and vendor programs (L16-L20). It is additive: the loan / lease agreements keep
 * their own party columns, which the existing billing, credit, delinquency and tax
 * processing still read. Every new contract registers its customer and accounting
 * organization at 100% (roles from the contract type's {@link FinanceContractAdapter});
 * {@link #backfillFinanceContractParties} registers existing contracts the same way.
 *
 * <p>{@code FinanceContract.parentFinanceContractId} links an individual contract to
 * a master contract; cycles are rejected.
 */
public final class FinanceContractPartyHelper {

    private static final String MODULE = FinanceContractPartyHelper.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    public static final String ROOT_ROLE = "FIN_CTR_ROLE";

    private FinanceContractPartyHelper() {
    }

    private static Map<String, Object> error(String key, Map<String, ? extends Object> args, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, key, args, locale));
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Whether a party row is effective at a moment (fromDate &lt;= asOf &lt; thruDate). */
    public static boolean isEffective(GenericValue party, Timestamp asOf) {
        Timestamp from = party.getTimestamp("fromDate");
        Timestamp thru = party.getTimestamp("thruDate");
        return (from == null || !from.after(asOf)) && (thru == null || thru.after(asOf));
    }

    /** Parties of a contract effective at {@code asOf} (null = now), optionally of one role. */
    public static List<GenericValue> findParties(Delegator delegator, String financeContractId, String roleTypeId,
            Timestamp asOf) throws GenericEntityException {
        Timestamp at = asOf == null ? UtilDateTime.nowTimestamp() : asOf;
        EntityQuery query = EntityQuery.use(delegator).from("FinanceContractParty").cache(false)
                .orderBy("roleTypeId", "fromDate", "partyId");
        query = UtilValidate.isEmpty(roleTypeId)
                ? query.where("financeContractId", financeContractId)
                : query.where("financeContractId", financeContractId, "roleTypeId", roleTypeId);
        List<GenericValue> result = new ArrayList<>();
        for (GenericValue party : query.queryList()) {
            if (isEffective(party, at)) {
                result.add(party);
            }
        }
        return result;
    }

    /** True when the role type is {@link #ROOT_ROLE} or one of its descendants. */
    public static boolean isContractRole(Delegator delegator, String roleTypeId) throws GenericEntityException {
        Set<String> visited = new HashSet<>();
        String current = roleTypeId;
        while (UtilValidate.isNotEmpty(current) && visited.add(current)) {
            GenericValue role = EntityQuery.use(delegator).from("RoleType").where("roleTypeId", current).cache().queryOne();
            if (role == null) {
                return false;
            }
            String parent = role.getString("parentTypeId");
            if (ROOT_ROLE.equals(parent)) {
                return true;
            }
            current = parent;
        }
        return false;
    }

    /** Ancestors of a contract (parent, grandparent, ...), nearest first. */
    public static List<String> ancestors(Delegator delegator, String financeContractId) throws GenericEntityException {
        List<String> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(financeContractId);
        String current = financeContractId;
        while (true) {
            GenericValue contract = EntityQuery.use(delegator).from("FinanceContract")
                    .where("financeContractId", current).cache(false).queryOne();
            String parent = contract == null ? null : contract.getString("parentFinanceContractId");
            if (UtilValidate.isEmpty(parent) || !visited.add(parent)) {
                return chain;
            }
            chain.add(parent);
            current = parent;
        }
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    private static void createParty(Delegator delegator, String financeContractId, String partyId, String roleTypeId,
            Timestamp fromDate, BigDecimal shareRate, boolean primary, String comments) throws GenericEntityException {
        GenericValue party = delegator.makeValue("FinanceContractParty");
        party.set("financeContractId", financeContractId);
        party.set("partyId", partyId);
        party.set("roleTypeId", roleTypeId);
        party.set("fromDate", fromDate);
        party.set("shareRate", shareRate);
        party.set("isPrimary", primary ? "Y" : "N");
        party.set("comments", comments);
        party.set("createdDate", UtilDateTime.nowTimestamp());
        party.create();
    }

    private static boolean hasRole(Delegator delegator, String financeContractId, String roleTypeId)
            throws GenericEntityException {
        return EntityQuery.use(delegator).from("FinanceContractParty")
                .where("financeContractId", financeContractId, "roleTypeId", roleTypeId).queryCount() > 0;
    }

    /**
     * Register the customer and (when given) the organization of a contract at 100%
     * in the roles of its contract type. A role that already has a row is skipped,
     * so the call is idempotent. Contract types without an adapter are skipped.
     *
     * @return the number of rows created
     */
    public static int registerPrimaryParties(Delegator delegator, GenericValue contract, String organizationPartyId)
            throws GenericEntityException {
        FinanceContractAdapter adapter = FinanceContractAdapters.forType(contract.getString("contractTypeId"));
        if (adapter == null) {
            return 0;
        }
        String financeContractId = contract.getString("financeContractId");
        Timestamp from = contract.getTimestamp("contractDate") != null ? contract.getTimestamp("contractDate")
                : contract.getTimestamp("createdDate") != null ? contract.getTimestamp("createdDate")
                : UtilDateTime.nowTimestamp();
        int created = 0;
        String customer = contract.getString("customerPartyId");
        if (UtilValidate.isNotEmpty(customer) && !hasRole(delegator, financeContractId, adapter.getCustomerRoleTypeId())) {
            createParty(delegator, financeContractId, customer, adapter.getCustomerRoleTypeId(), from, BigDecimal.ONE, true, null);
            created++;
        }
        if (UtilValidate.isNotEmpty(organizationPartyId)
                && !hasRole(delegator, financeContractId, adapter.getOrganizationRoleTypeId())) {
            createParty(delegator, financeContractId, organizationPartyId, adapter.getOrganizationRoleTypeId(), from,
                    BigDecimal.ONE, true, null);
            created++;
        }
        return created;
    }

    // ------------------------------------------------------------------
    // Services
    // ------------------------------------------------------------------

    /**
     * Add a party in a contract role. The role must be under FIN_CTR_ROLE, the
     * share (if any) in (0, 1], the same party may not be effective twice in the
     * same role, and the effective shares of one role may not exceed 100%.
     */
    public static Map<String, Object> addFinanceContractParty(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String financeContractId = (String) context.get("financeContractId");
        String partyId = (String) context.get("partyId");
        String roleTypeId = (String) context.get("roleTypeId");
        BigDecimal shareRate = (BigDecimal) context.get("shareRate");
        Timestamp fromDate = context.get("fromDate") != null ? (Timestamp) context.get("fromDate") : UtilDateTime.nowTimestamp();
        try {
            if (EntityQuery.use(delegator).from("FinanceContract").where("financeContractId", financeContractId).queryOne() == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "FinanceContract", "id", financeContractId), locale);
            }
            if (EntityQuery.use(delegator).from("Party").where("partyId", partyId).queryOne() == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "Party", "id", partyId), locale);
            }
            if (!isContractRole(delegator, roleTypeId)) {
                return error("FinanceContractPartyRoleInvalid", UtilMisc.toMap("roleTypeId", roleTypeId), locale);
            }
            if (shareRate != null && (shareRate.signum() <= 0 || shareRate.compareTo(BigDecimal.ONE) > 0)) {
                return error("FinanceContractPartyShareInvalid", UtilMisc.toMap("shareRate", shareRate), locale);
            }
            BigDecimal shares = shareRate == null ? BigDecimal.ZERO : shareRate;
            for (GenericValue existing : findParties(delegator, financeContractId, roleTypeId, fromDate)) {
                if (partyId.equals(existing.getString("partyId"))) {
                    return error("FinanceContractPartyDuplicate", UtilMisc.toMap("partyId", partyId,
                            "roleTypeId", roleTypeId), locale);
                }
                if (existing.getBigDecimal("shareRate") != null) {
                    shares = shares.add(existing.getBigDecimal("shareRate"));
                }
            }
            if (shareRate != null && shares.compareTo(BigDecimal.ONE) > 0) {
                return error("FinanceContractPartyShareExceeded", UtilMisc.toMap("roleTypeId", roleTypeId,
                        "total", shares), locale);
            }
            createParty(delegator, financeContractId, partyId, roleTypeId, fromDate, shareRate,
                    "Y".equals(context.get("isPrimary")), (String) context.get("comments"));
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("fromDate", fromDate);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** End a party's role on a contract (sets thruDate, default now; not before fromDate). */
    public static Map<String, Object> expireFinanceContractParty(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        Timestamp thruDate = context.get("thruDate") != null ? (Timestamp) context.get("thruDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue party = EntityQuery.use(delegator).from("FinanceContractParty")
                    .where("financeContractId", context.get("financeContractId"), "partyId", context.get("partyId"),
                            "roleTypeId", context.get("roleTypeId"), "fromDate", context.get("fromDate")).queryOne();
            if (party == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "FinanceContractParty",
                        "id", context.get("financeContractId") + "/" + context.get("partyId")), locale);
            }
            if (party.getTimestamp("thruDate") != null) {
                return error("FinanceContractPartyAlreadyExpired", UtilMisc.toMap("partyId", party.getString("partyId")), locale);
            }
            if (thruDate.before(party.getTimestamp("fromDate"))) {
                return error("FinanceContractPartyThruBeforeFrom", UtilMisc.toMap("thruDate", thruDate), locale);
            }
            party.set("thruDate", thruDate);
            party.store();
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /**
     * Set (or clear, when empty) the parent contract. Rejects a missing parent,
     * the contract itself and any parent whose ancestors include the contract.
     */
    public static Map<String, Object> setFinanceContractParent(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String financeContractId = (String) context.get("financeContractId");
        String parentId = (String) context.get("parentFinanceContractId");
        try {
            GenericValue contract = EntityQuery.use(delegator).from("FinanceContract")
                    .where("financeContractId", financeContractId).queryOne();
            if (contract == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "FinanceContract", "id", financeContractId), locale);
            }
            if (UtilValidate.isNotEmpty(parentId)) {
                if (EntityQuery.use(delegator).from("FinanceContract").where("financeContractId", parentId).queryOne() == null) {
                    return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "FinanceContract", "id", parentId), locale);
                }
                if (parentId.equals(financeContractId) || ancestors(delegator, parentId).contains(financeContractId)) {
                    return error("FinanceContractParentCycle", UtilMisc.toMap("financeContractId", financeContractId,
                            "parentFinanceContractId", parentId), locale);
                }
            }
            contract.set("parentFinanceContractId", UtilValidate.isEmpty(parentId) ? null : parentId);
            contract.store();
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /**
     * Register the primary parties (customer and organization at 100%) of every
     * existing contract that lacks them. Idempotent; contract types without an
     * adapter, and agreements without an organization, are skipped for that role.
     */
    public static Map<String, Object> backfillFinanceContractParties(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        long contracts = 0;
        long created = 0;
        try {
            for (GenericValue contract : EntityQuery.use(delegator).from("FinanceContract")
                    .orderBy("financeContractId").queryList()) {
                FinanceContractAdapter adapter = FinanceContractAdapters.forType(contract.getString("contractTypeId"));
                if (adapter == null) {
                    continue;
                }
                String organizationPartyId = null;
                GenericValue agreement = adapter.findAgreement(delegator, contract.getString("financeContractId"));
                if (agreement != null) {
                    organizationPartyId = adapter.getOrganizationPartyId(delegator, agreement);
                }
                int n = registerPrimaryParties(delegator, contract, organizationPartyId);
                if (n > 0) {
                    contracts++;
                    created += n;
                }
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("updatedContractCount", contracts);
            result.put("createdPartyCount", created);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
