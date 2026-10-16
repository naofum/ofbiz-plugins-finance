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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.party.party.PartyHelper;

/**
 * Lease inquiries and documents (STEP L8). Pure reads; no business change except
 * recording the issue date of the acceptance certificate.
 *
 * <ul>
 * <li>Collection status per agreement (from {@link LeaseAccountingServices#computeBalance}).</li>
 * <li>Outstanding principal per lessee (sum over the lessee's ACTIVE agreements).</li>
 * <li>Asset register: agreement asset x agreement x fixed asset x status x settlement.</li>
 * <li>Data of the acceptance certificate (物件受領書) and the expiry notice (満了通知書).</li>
 * </ul>
 */
public final class LeaseInquiryHelper {

    private static final String RESOURCE = "FinanceUiLabels";

    public static final String DOC_ACCEPTANCE = "ACCEPTANCE";
    public static final String DOC_EXPIRY = "EXPIRY";

    private LeaseInquiryHelper() {
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String partyName(Delegator delegator, String partyId) {
        return UtilValidate.isEmpty(partyId) ? "" : PartyHelper.getPartyName(delegator, partyId, false);
    }

    private static List<GenericValue> agreements(Delegator delegator, List<String> statusIds, String lesseePartyId)
            throws GenericEntityException {
        List<EntityCondition> conditions = new ArrayList<>();
        conditions.add(EntityCondition.makeCondition("statusId", EntityOperator.IN, statusIds));
        if (UtilValidate.isNotEmpty(lesseePartyId)) {
            conditions.add(EntityCondition.makeCondition("lesseePartyId", EntityOperator.EQUALS, lesseePartyId));
        }
        return EntityQuery.use(delegator).from("LeaseAgreement")
                .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                .orderBy("lesseePartyId", "leaseAgreementId").cache(false).queryList();
    }

    /** Statuses shown by the inquiries: the given one, or ACTIVE and COMPLETED. */
    private static List<String> statuses(String statusId) {
        List<String> list = new ArrayList<>();
        if (UtilValidate.isNotEmpty(statusId)) {
            list.add(statusId);
        } else {
            list.add("LEASEAGR_ACTIVE");
            list.add("LEASEAGR_TERMINATED");
            list.add(LeaseSplitServices.STATUS_SPLIT);
            list.add("LEASEAGR_COMPLETED");
        }
        return list;
    }

    /** Collection status per agreement (契約別リース料回収状況). */
    public static List<Map<String, Object>> collectionStatus(Delegator delegator, String statusId, String lesseePartyId,
            Timestamp asOfDate) throws GenericEntityException {
        Timestamp asOf = UtilDateTime.getDayEnd(asOfDate == null ? UtilDateTime.nowTimestamp() : asOfDate);
        LeaseContractAdapter adapter = new LeaseContractAdapter();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GenericValue agreement : agreements(delegator, statuses(statusId), lesseePartyId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("leaseAgreementId", agreement.getString("leaseAgreementId"));
            row.put("financeContractId", agreement.getString("financeContractId"));
            row.put("lesseePartyId", agreement.getString("lesseePartyId"));
            row.put("statusId", agreement.getString("statusId"));
            row.put("currencyUomId", agreement.getString("currencyUomId"));
            row.put("leaseEndDate", agreement.getTimestamp("leaseEndDate"));
            row.put("totalLeaseAmount", agreement.getBigDecimal("totalLeaseAmount"));
            row.putAll(LeaseAccountingServices.computeBalance(delegator, agreement));
            row.put("daysPastDue", LeaseTerminationServices.isCollectible(agreement)
                    ? adapter.computeDelinquency(delegator, agreement, asOf).getDaysPastDue() : 0L);
            rows.add(row);
        }
        return rows;
    }

    /** Outstanding principal per lessee and currency over ACTIVE agreements (未回収元本残高一覧). */
    public static List<Map<String, Object>> outstandingPrincipalByLessee(Delegator delegator, String lesseePartyId)
            throws GenericEntityException {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (GenericValue agreement : agreements(delegator, statuses("LEASEAGR_ACTIVE"), lesseePartyId)) {
            Map<String, Object> balance = LeaseAccountingServices.computeBalance(delegator, agreement);
            String key = agreement.getString("lesseePartyId") + "|" + agreement.getString("currencyUomId");
            Map<String, Object> row = byKey.get(key);
            if (row == null) {
                row = new LinkedHashMap<>();
                row.put("lesseePartyId", agreement.getString("lesseePartyId"));
                row.put("currencyUomId", agreement.getString("currencyUomId"));
                row.put("agreementCount", 0L);
                row.put("acquisitionCost", BigDecimal.ZERO);
                row.put("outstandingPrincipal", BigDecimal.ZERO);
                row.put("unpaidBilledAmount", BigDecimal.ZERO);
                row.put("unbilledLeaseAmount", BigDecimal.ZERO);
                row.put("deferredProfitBalance", BigDecimal.ZERO);
                byKey.put(key, row);
            }
            row.put("agreementCount", (Long) row.get("agreementCount") + 1L);
            row.put("acquisitionCost", ((BigDecimal) row.get("acquisitionCost")).add(nz(agreement.getBigDecimal("acquisitionCost"))));
            for (String f : new String[] {"outstandingPrincipal", "unpaidBilledAmount", "unbilledLeaseAmount",
                "deferredProfitBalance"}) {
                row.put(f, ((BigDecimal) row.get(f)).add(nz((BigDecimal) balance.get(f))));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /** Asset register (物件台帳): every agreement asset with its agreement, fixed asset and settlement. */
    public static List<Map<String, Object>> assetRegister(Delegator delegator, String assetStatusId, String lesseePartyId,
            String fixedAssetTypeId) throws GenericEntityException {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<EntityCondition> conditions = new ArrayList<>();
        if (UtilValidate.isNotEmpty(assetStatusId)) {
            conditions.add(EntityCondition.makeCondition("assetStatusId", EntityOperator.EQUALS, assetStatusId));
        } else {
            conditions.add(EntityCondition.makeCondition("assetStatusId", EntityOperator.NOT_EQUAL, null));
        }
        if (UtilValidate.isNotEmpty(fixedAssetTypeId)) {
            conditions.add(EntityCondition.makeCondition("fixedAssetTypeId", EntityOperator.EQUALS, fixedAssetTypeId));
        }
        for (GenericValue asset : EntityQuery.use(delegator).from("LeaseAgreementAsset")
                .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                .orderBy("leaseAgreementId", "assetSeqId").cache(false).queryList()) {
            GenericValue agreement = EntityQuery.use(delegator).from("LeaseAgreement")
                    .where("leaseAgreementId", asset.getString("leaseAgreementId")).cache(false).queryOne();
            if (agreement == null || (UtilValidate.isNotEmpty(lesseePartyId)
                    && !lesseePartyId.equals(agreement.getString("lesseePartyId")))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(asset.getAllFields());
            row.put("lesseePartyId", agreement.getString("lesseePartyId"));
            row.put("agreementStatusId", agreement.getString("statusId"));
            row.put("leaseClassEnumId", agreement.getString("leaseClassEnumId"));
            row.put("currencyUomId", agreement.getString("currencyUomId"));
            row.put("leaseStartDate", agreement.getTimestamp("leaseStartDate"));
            row.put("leaseEndDate", agreement.getTimestamp("leaseEndDate"));
            if (UtilValidate.isNotEmpty(asset.getString("fixedAssetId"))) {
                GenericValue fixedAsset = EntityQuery.use(delegator).from("FixedAsset")
                        .where("fixedAssetId", asset.getString("fixedAssetId")).queryOne();
                row.put("ownerPartyId", fixedAsset == null ? null : fixedAsset.getString("partyId"));
            }
            GenericValue settlement = EntityQuery.use(delegator).from("LeaseAssetSettlement")
                    .where("leaseAgreementId", asset.getString("leaseAgreementId"), "assetSeqId", asset.getString("assetSeqId"))
                    .queryOne();
            if (settlement != null) {
                row.put("returnDate", settlement.getTimestamp("returnDate"));
                row.put("transferDate", settlement.getTimestamp("transferDate"));
                row.put("disposalDate", settlement.getTimestamp("disposalDate"));
                row.put("disposalTypeEnumId", settlement.getString("disposalTypeEnumId"));
            }
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Documents
    // ------------------------------------------------------------------

    /** Document data: "parameters" (header, labels) and "rows" (asset lines), or null when not available. */
    public static Map<String, Object> documentData(Delegator delegator, String documentType, String leaseAgreementId,
            Locale locale) throws GenericEntityException {
        GenericValue agreement = EntityQuery.use(delegator).from("LeaseAgreement")
                .where("leaseAgreementId", leaseAgreementId).queryOne();
        if (agreement == null) {
            return null;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        boolean acceptance = DOC_ACCEPTANCE.equals(documentType);
        GenericValue record;
        if (acceptance) {
            record = EntityQuery.use(delegator).from("LeaseAcceptance").where("leaseAgreementId", leaseAgreementId).queryOne();
            if (record == null) {
                return null;
            }
            p.put("documentTitle", msg("FinanceLeaseDocAcceptanceTitle", locale));
            p.put("documentDate", record.getTimestamp("receiptPrintedDate") != null
                    ? record.getTimestamp("receiptPrintedDate") : UtilDateTime.nowTimestamp());
            p.put("bodyText", msg("FinanceLeaseDocAcceptanceBody", locale));
            p.put("line1Label", msg("FinanceLeaseAcceptanceDate", locale));
            p.put("line1Value", formatDate(record.getTimestamp("acceptanceDate")));
            p.put("line2Label", msg("FinanceLeaseAcceptedBy", locale));
            p.put("line2Value", partyName(delegator, record.getString("acceptedByPartyId")));
        } else if (DOC_EXPIRY.equals(documentType)) {
            record = EntityQuery.use(delegator).from("LeaseExpiryNotice").where("leaseAgreementId", leaseAgreementId).queryOne();
            if (record == null) {
                return null;
            }
            p.put("documentTitle", msg("FinanceLeaseDocExpiryTitle", locale));
            p.put("documentDate", record.getTimestamp("noticeDate"));
            boolean transfer = LeaseProductHelper.CLASS_FIN_TRANSFER.equals(agreement.getString("leaseClassEnumId"));
            p.put("bodyText", msg(transfer ? "FinanceLeaseDocExpiryBodyTransfer" : "FinanceLeaseDocExpiryBodyReturn", locale));
            p.put("line1Label", msg("FinanceLeaseStartDate", locale));
            p.put("line1Value", formatDate(agreement.getTimestamp("leaseStartDate")));
            p.put("line2Label", msg("FinanceLeaseEndDate", locale));
            p.put("line2Value", formatDate(agreement.getTimestamp("leaseEndDate")));
        } else {
            return null;
        }
        p.put("documentNoLabel", msg("FinanceLeaseAgreementId", locale));
        p.put("documentNo", leaseAgreementId);
        p.put("lessorName", partyName(delegator, agreement.getString("organizationPartyId")));
        p.put("lesseeName", partyName(delegator, agreement.getString("lesseePartyId")));
        p.put("currencyUomId", agreement.getString("currencyUomId"));
        for (String key : new String[] {"FinanceLeasePdfIssueDate", "FinanceLeasePdfLessor", "FinanceLeasePdfLessee",
            "FinanceLeaseAssetSeqId", "FinanceLeaseAssetName", "FinanceLeaseAssetQuantity", "FinanceLeaseFixedAssetId",
            "FinanceLeaseAcquisitionCost", "FinanceQuotePdfPage"}) {
            p.put(key, msg(key, locale));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GenericValue asset : EntityQuery.use(delegator).from("LeaseAgreementAsset")
                .where("leaseAgreementId", leaseAgreementId).orderBy("assetSeqId").queryList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assetSeqId", asset.getString("assetSeqId"));
            row.put("assetName", asset.getString("assetName"));
            row.put("quantity", asset.getBigDecimal("quantity"));
            row.put("fixedAssetId", asset.getString("fixedAssetId"));
            row.put("acquisitionCost", asset.getBigDecimal("acquisitionCost"));
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parameters", p);
        data.put("rows", rows);
        return data;
    }

    /** Record the first issue date of the acceptance certificate (no-op when already issued). */
    public static void markAcceptanceCertificateIssued(Delegator delegator, String leaseAgreementId)
            throws GenericEntityException {
        GenericValue acceptance = EntityQuery.use(delegator).from("LeaseAcceptance")
                .where("leaseAgreementId", leaseAgreementId).queryOne();
        if (acceptance != null && acceptance.getTimestamp("receiptPrintedDate") == null) {
            acceptance.set("receiptPrintedDate", UtilDateTime.nowTimestamp());
            acceptance.store();
        }
    }

    private static String msg(String key, Locale locale) {
        return UtilProperties.getMessage(RESOURCE, key, locale);
    }

    private static String formatDate(Timestamp ts) {
        return ts == null ? "" : ts.toLocalDateTime().toLocalDate().toString();
    }
}
