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
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
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
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.finance.common.FinanceContractHelper;
import org.apache.ofbiz.finance.common.FinanceGlHelper;
import org.apache.ofbiz.finance.common.FinanceReversalHelper;
import org.apache.ofbiz.finance.common.FinanceWorkEffortHelper;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Reversal of mistaken lease operations (取消, STEP L15a): the acceptance (lease
 * start), the early termination and the change of terms. An operation can be
 * reversed only when nothing was processed after it (billing, payment, deferred
 * profit release, depreciation, ...); the reversal posts reversing GL entries,
 * restores the schedule as a new revision where needed, restores the statuses and
 * keeps a {@code FinanceReversal} record. FINANCE_ADMIN only; a reason is required.
 */
public final class LeaseReversalServices {

    private static final String MODULE = LeaseReversalServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    private LeaseReversalServices() {
    }

    private static Map<String, Object> error(String key, Map<String, ? extends Object> args, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, key, args, locale));
    }

    private static Map<String, Object> blocked(String what, Locale locale) {
        return error("FinanceReversalBlocked", UtilMisc.toMap("what", what), locale);
    }

    private static void check(Map<String, Object> result) throws GenericServiceException {
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(result));
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static GenericValue findAgreement(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseAgreement").where("leaseAgreementId", leaseAgreementId)
                .cache(false).queryOne();
    }

    private static long count(Delegator delegator, String entity, Object... fv) throws GenericEntityException {
        return FinanceReversalHelper.countCreatedAfter(delegator, entity, null, fv);
    }

    private static long countNot(Delegator delegator, String entity, String field, Object notValue, Object... fv)
            throws GenericEntityException {
        List<EntityCondition> conds = new ArrayList<>();
        for (int i = 0; i + 1 < fv.length; i += 2) {
            conds.add(EntityCondition.makeCondition((String) fv[i], fv[i + 1]));
        }
        conds.add(EntityCondition.makeCondition(field, EntityOperator.NOT_EQUAL, notValue));
        return EntityQuery.use(delegator).from(entity).where(EntityCondition.makeCondition(conds, EntityOperator.AND))
                .cache(false).queryCount();
    }

    /** Delinquency records of the contract (created after {@code after}, or any when null). */
    private static boolean hasDelinquency(Delegator delegator, String financeContractId, Timestamp after)
            throws GenericEntityException {
        for (String e : UtilMisc.toList("LoanDelinquency", "LoanLateFeeAccrual", "LoanDunningNotice", "LoanDelinquencyAction")) {
            if (FinanceReversalHelper.countCreatedAfter(delegator, e, after, "financeContractId", financeContractId) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copy a schedule revision (rows and component rows) as a new revision and set
     * the component definition totals from it. Returns the sum of the row taxes.
     */
    static BigDecimal copyRevision(Delegator delegator, String leaseAgreementId, long fromRevision, long toRevision)
            throws GenericEntityException {
        BigDecimal totalTax = BigDecimal.ZERO;
        for (GenericValue row : EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                .where("leaseAgreementId", leaseAgreementId, "scheduleRevisionNumber", fromRevision).queryList()) {
            GenericValue line = delegator.makeValue("LeaseAgreementPaymentSchedule", row.getAllFields());
            line.set("scheduleRevisionNumber", toRevision);
            line.create();
            totalTax = totalTax.add(nz(row.getBigDecimal("taxAmount")));
        }
        for (GenericValue def : EntityQuery.use(delegator).from("LeaseAgreementComponent")
                .where("leaseAgreementId", leaseAgreementId).queryList()) {
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal tax = BigDecimal.ZERO;
            for (GenericValue c : EntityQuery.use(delegator).from("LeaseAgrPaySchedComp").where("leaseAgreementId",
                    leaseAgreementId, "scheduleRevisionNumber", fromRevision, "componentSeqId", def.getString("componentSeqId"))
                    .queryList()) {
                GenericValue copy = delegator.makeValue("LeaseAgrPaySchedComp", c.getAllFields());
                copy.set("scheduleRevisionNumber", toRevision);
                copy.create();
                total = total.add(nz(c.getBigDecimal("amount")));
                tax = tax.add(nz(c.getBigDecimal("taxAmount")));
            }
            def.set("totalAmount", total);
            def.set("totalTaxAmount", tax);
            def.store();
        }
        return totalTax;
    }

    // ------------------------------------------------------------------
    // Acceptance (lease start)
    // ------------------------------------------------------------------

    public static Map<String, Object> reverseLeaseAcceptance(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String reason = (String) context.get("reason");
        try {
            if (FinanceReversalHelper.reasonMissing(reason)) {
                return error("FinanceReversalReasonRequired", UtilMisc.toMap(), locale);
            }
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            String contractId = agreement.getString("financeContractId");
            FinanceContractHelper.lock(delegator, contractId);
            agreement = findAgreement(delegator, leaseAgreementId);
            GenericValue acceptance = EntityQuery.use(delegator).from("LeaseAcceptance")
                    .where("leaseAgreementId", leaseAgreementId).cache(false).queryOne();
            if (!"LEASEAGR_ACTIVE".equals(agreement.getString("statusId")) || acceptance == null) {
                return error("FinanceReversalNotApplicable", UtilMisc.toMap("id", leaseAgreementId,
                        "statusId", agreement.getString("statusId")), locale);
            }
            // Nothing may have been processed after the acceptance.
            if (count(delegator, "LeaseInstallmentInvoice", "leaseAgreementId", leaseAgreementId) > 0) {
                return blocked("billing", locale);
            }
            if (count(delegator, "LeaseProfitRecognition", "leaseAgreementId", leaseAgreementId) > 0) {
                return blocked("deferred profit release", locale);
            }
            if (count(delegator, "LeaseAssetDepreciation", "leaseAgreementId", leaseAgreementId) > 0) {
                return blocked("depreciation", locale);
            }
            if (count(delegator, "LeasePaymentReceipt", "leaseAgreementId", leaseAgreementId) > 0) {
                return blocked("payment", locale);
            }
            if (countNot(delegator, "LeaseReLease", "statusId", LeaseReLeaseServices.STATUS_CANCELLED,
                    "leaseAgreementId", leaseAgreementId) > 0) {
                return blocked("re-lease", locale);
            }
            if (count(delegator, "LeaseModification", "leaseAgreementId", leaseAgreementId, "reversedDate", null) > 0) {
                return blocked("change of terms", locale);
            }
            if (countNot(delegator, "LeaseMaintRecord", "statusId", LeaseMaintenanceServices.STATUS_CANCELLED,
                    "leaseAgreementId", leaseAgreementId) > 0) {
                return blocked("maintenance", locale);
            }
            if (count(delegator, "LeaseAssetSettlement", "leaseAgreementId", leaseAgreementId) > 0
                    || count(delegator, "LeaseExpiryNotice", "leaseAgreementId", leaseAgreementId) > 0) {
                return blocked("expiry", locale);
            }
            if (count(delegator, "LeasePublicChargeDeclAsset", "leaseAgreementId", leaseAgreementId) > 0) {
                return blocked("public charge declaration", locale);
            }
            if (hasDelinquency(delegator, contractId, null)) {
                return blocked("delinquency", locale);
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            String description = "Reversal of lease start " + leaseAgreementId;
            List<String> reversing = new ArrayList<>();
            reversing.add(FinanceGlHelper.reverseTransaction(delegator, dispatcher, acceptance.getString("startAcctgTransId"),
                    description, now, userLogin));
            reversing.add(FinanceGlHelper.reverseTransaction(delegator, dispatcher,
                    acceptance.getString("startTaxAcctgTransId"), description, now, userLogin));

            // Restore the pre-acceptance schedule as a new revision.
            long current = LeaseAccountingServices.currentScheduleRevision(delegator, leaseAgreementId);
            long base = acceptance.getLong("scheduleRevisionNumber") - 1;
            copyRevision(delegator, leaseAgreementId, base, current + 1);

            GenericValue quote = EntityQuery.use(delegator).from("LeaseQuote")
                    .where("leaseQuoteId", agreement.getString("leaseQuoteId")).queryOne();
            agreement.set("statusId", "LEASEAGR_CONTRACTED");
            agreement.set("leaseStartDate", null);
            agreement.set("leaseEndDate", null);
            agreement.set("firstPaymentDate", quote == null ? null : quote.get("firstPaymentDate"));
            agreement.set("taxRate", quote == null ? null : quote.get("taxRate"));
            agreement.set("totalTaxAmount", quote == null ? null : quote.get("totalTaxAmount"));
            agreement.set("taxTimingEnumId", null);
            agreement.set("taxAuthPartyId", null);
            agreement.set("taxAuthGeoId", null);
            agreement.set("taxAuthorityRateSeqId", null);
            agreement.store();
            FinanceContractHelper.updateStatus(delegator, contractId, FinanceContractHelper.STATUS_CONTRACTED);

            boolean operating = LeaseProductHelper.isOperating(agreement);
            for (GenericValue asset : EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId).queryList()) {
                if ("LSAST_LEASED".equals(asset.getString("assetStatusId"))) {
                    asset.set("assetStatusId", LeaseAcquisitionServices.ASSET_PURCHASED);
                    asset.store();
                }
                if (operating && UtilValidate.isNotEmpty(asset.getString("fixedAssetId"))) {
                    GenericValue fa = EntityQuery.use(delegator).from("FixedAsset")
                            .where("fixedAssetId", asset.getString("fixedAssetId")).queryOne();
                    if (fa != null) {
                        fa.set("salvageValue", null);
                        fa.set("expectedEndOfLife", null);
                        fa.set("depreciation", BigDecimal.ZERO);
                        fa.store();
                    }
                }
            }
            // Tasks created after the start (expiry notice) are cancelled and unlinked.
            for (GenericValue link : EntityQuery.use(delegator).from("FinanceContractWorkEffort")
                    .where("financeContractId", contractId, "financeWorkTypeEnumId", FinanceWorkEffortHelper.TYPE_EXPIRY_NOTICE)
                    .queryList()) {
                FinanceWorkEffortHelper.cancelAndUnlinkTask(delegator, dispatcher, contractId,
                        FinanceWorkEffortHelper.TYPE_EXPIRY_NOTICE, link.getString("triggerKey"), userLogin);
            }

            String snapshot = FinanceReversalHelper.snapshot(acceptance);
            Timestamp operationDate = acceptance.getTimestamp("acceptanceDate");
            acceptance.remove();
            String reversalId = FinanceReversalHelper.record(delegator, contractId, FinanceReversalHelper.TYPE_LEASE_ACCEPT,
                    leaseAgreementId, operationDate, reason, reversing, snapshot, userLogin);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("financeReversalId", reversalId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Early termination
    // ------------------------------------------------------------------

    public static Map<String, Object> reverseLeaseTermination(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String reason = (String) context.get("reason");
        try {
            if (FinanceReversalHelper.reasonMissing(reason)) {
                return error("FinanceReversalReasonRequired", UtilMisc.toMap(), locale);
            }
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            String contractId = agreement.getString("financeContractId");
            FinanceContractHelper.lock(delegator, contractId);
            agreement = findAgreement(delegator, leaseAgreementId);
            GenericValue t = LeaseTerminationServices.findTermination(delegator, leaseAgreementId);
            if (!LeaseTerminationServices.isTerminated(agreement) || t == null) {
                return error("FinanceReversalNotApplicable", UtilMisc.toMap("id", leaseAgreementId,
                        "statusId", agreement.getString("statusId")), locale);
            }
            Timestamp after = t.getTimestamp("createdStamp");
            GenericValue damagesInvoice = UtilValidate.isEmpty(t.getString("invoiceId")) ? null
                    : EntityQuery.use(delegator).from("Invoice").where("invoiceId", t.getString("invoiceId")).cache(false).queryOne();
            if (damagesInvoice != null && (LeaseReLeaseServices.applied(delegator, damagesInvoice.getString("invoiceId")).signum() > 0
                    || !"INVOICE_READY".equals(damagesInvoice.getString("statusId")))) {
                return blocked("payment of the damages", locale);
            }
            if (FinanceReversalHelper.countCreatedAfter(delegator, "LeasePaymentReceipt", after,
                    "leaseAgreementId", leaseAgreementId) > 0) {
                return blocked("payment", locale);
            }
            if (count(delegator, "LeaseAssetSettlement", "leaseAgreementId", leaseAgreementId) > 0) {
                return blocked("asset recovery", locale);
            }
            if (hasDelinquency(delegator, contractId, after)) {
                return blocked("delinquency", locale);
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            String description = "Reversal of lease termination " + leaseAgreementId;
            List<String> reversing = new ArrayList<>();
            // Damages invoice: the core cancellation reverses its GL entries.
            if (damagesInvoice != null) {
                check(dispatcher.runSync("setInvoiceStatus", UtilMisc.toMap("invoiceId", damagesInvoice.getString("invoiceId"),
                        "statusId", "INVOICE_CANCELLED", "userLogin", userLogin)));
                FinanceGlHelper.postUnposted(delegator, dispatcher, "invoiceId", damagesInvoice.getString("invoiceId"), userLogin);
            }
            reversing.add(FinanceGlHelper.reverseTransaction(delegator, dispatcher, t.getString("acctgTransId"),
                    description, now, userLogin));

            // Re-lease: undo the recognition at termination; restore the cancelled contracted periods.
            Timestamp termDay = UtilDateTime.getDayStart(t.getTimestamp("terminationDate"));
            for (GenericValue r : EntityQuery.use(delegator).from("LeaseReLease").where("leaseAgreementId", leaseAgreementId)
                    .orderBy("reLeaseSeqId").queryList()) {
                if (LeaseReLeaseServices.STATUS_CANCELLED.equals(r.getString("statusId"))
                        && LeaseReLeaseServices.TERMINATION_CANCEL_MARK.equals(r.getString("comments"))) {
                    r.set("statusId", LeaseReLeaseServices.STATUS_CONTRACTED);
                    r.set("comments", null);
                    r.store();
                    continue;
                }
                if (!LeaseReLeaseServices.STATUS_COMPLETED.equals(r.getString("statusId"))
                        || !r.getTimestamp("thruDate").after(termDay)) {
                    continue;
                }
                GenericValue last = EntityQuery.use(delegator).from("LeaseReLeaseRecognition")
                        .where("leaseAgreementId", leaseAgreementId, "reLeaseSeqId", r.getString("reLeaseSeqId"))
                        .orderBy("-monthNumber").cache(false).queryFirst();
                if (last == null || !UtilDateTime.getDayStart(last.getTimestamp("thruDate")).equals(termDay)) {
                    continue;
                }
                reversing.add(FinanceGlHelper.reverseTransaction(delegator, dispatcher, last.getString("acctgTransId"),
                        description, now, userLogin));
                BigDecimal before = nz(last.getBigDecimal("cumulativeAmount")).subtract(nz(last.getBigDecimal("amount")));
                last.remove();
                r.set("recognizedAmount", before);
                r.set("statusId", LeaseReLeaseServices.STATUS_ACTIVE);
                r.store();
            }

            // Maintenance plans ended by the termination and their cancelled maintenances.
            Timestamp nextDay = UtilDateTime.getDayStart(UtilDateTime.adjustTimestamp(termDay, Calendar.DAY_OF_MONTH, 1));
            Set<String> plans = new HashSet<>();
            for (GenericValue plan : EntityQuery.use(delegator).from("LeaseMaintPlan").where("leaseAgreementId", leaseAgreementId)
                    .queryList()) {
                if (plan.getTimestamp("thruDate") != null && plan.getTimestamp("thruDate").equals(nextDay)) {
                    plan.set("thruDate", null);
                    plan.store();
                    plans.add(plan.getString("maintPlanSeqId"));
                }
            }
            for (GenericValue rec : EntityQuery.use(delegator).from("LeaseMaintRecord")
                    .where("leaseAgreementId", leaseAgreementId, "statusId", LeaseMaintenanceServices.STATUS_CANCELLED).queryList()) {
                if (!plans.contains(rec.getString("maintPlanSeqId")) || !"Plan ended".equals(rec.getString("comments"))
                        || rec.getTimestamp("scheduledDate").before(nextDay)) {
                    continue;
                }
                String key = LeaseMaintenanceServices.triggerKey(rec.getString("maintPlanSeqId"),
                        rec.getTimestamp("scheduledDate"), rec.getString("leaseMaintRecordId"));
                GenericValue link = FinanceWorkEffortHelper.findLink(delegator, contractId,
                        FinanceWorkEffortHelper.TYPE_MAINTENANCE, key);
                if (link != null) {
                    link.remove();
                }
                GenericValue task = EntityQuery.use(delegator).from("WorkEffort").where("workEffortId", rec.getString("workEffortId"))
                        .queryOne();
                FinanceWorkEffortHelper.Task newTask = FinanceWorkEffortHelper.ensureTask(delegator, dispatcher, contractId,
                        FinanceWorkEffortHelper.TYPE_MAINTENANCE, key,
                        task == null ? "Maintenance " + leaseAgreementId : task.getString("workEffortName"),
                        task == null ? null : task.getString("description"), rec.getTimestamp("scheduledDate"),
                        rec.getTimestamp("scheduledDate"), null, rec.getString("fixedAssetId"), userLogin);
                rec.set("statusId", LeaseMaintenanceServices.STATUS_SCHEDULED);
                rec.set("workEffortId", newTask.getWorkEffortId());
                rec.set("comments", null);
                rec.store();
                GenericValue fam = EntityQuery.use(delegator).from("FixedAssetMaint").where("fixedAssetId",
                        rec.getString("fixedAssetId"), "maintHistSeqId", rec.getString("maintHistSeqId")).queryOne();
                if (fam != null) {
                    fam.set("statusId", "FAM_SCHEDULED");
                    fam.set("scheduleWorkEffortId", newTask.getWorkEffortId());
                    fam.store();
                }
            }

            // Recovery tasks.
            for (GenericValue asset : EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId).queryList()) {
                FinanceWorkEffortHelper.cancelAndUnlinkTask(delegator, dispatcher, contractId,
                        FinanceWorkEffortHelper.TYPE_ASSET_RETURN, asset.getString("assetSeqId"), userLogin);
            }

            agreement.set("statusId", "LEASEAGR_ACTIVE");
            agreement.store();
            String snapshot = FinanceReversalHelper.snapshot(t);
            Timestamp operationDate = t.getTimestamp("terminationDate");
            t.remove();
            String reversalId = FinanceReversalHelper.record(delegator, contractId, FinanceReversalHelper.TYPE_LEASE_TERM,
                    leaseAgreementId, operationDate, reason, reversing, snapshot, userLogin);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("financeReversalId", reversalId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Change of terms
    // ------------------------------------------------------------------

    public static Map<String, Object> reverseLeaseModification(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String reason = (String) context.get("reason");
        try {
            if (FinanceReversalHelper.reasonMissing(reason)) {
                return error("FinanceReversalReasonRequired", UtilMisc.toMap(), locale);
            }
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            String contractId = agreement.getString("financeContractId");
            FinanceContractHelper.lock(delegator, contractId);
            agreement = findAgreement(delegator, leaseAgreementId);
            GenericValue m = EntityQuery.use(delegator).from("LeaseModification")
                    .where("leaseAgreementId", leaseAgreementId, "reversedDate", null)
                    .orderBy("-leaseModificationId").cache(false).queryFirst();
            long current = LeaseAccountingServices.currentScheduleRevision(delegator, leaseAgreementId);
            // The latest not reversed change only; the schedule goes back to the revision before it.
            if (m == null || !"LEASEAGR_ACTIVE".equals(agreement.getString("statusId")) || m.getLong("fromRevisionNumber") == null) {
                return error("FinanceReversalNotApplicable", UtilMisc.toMap("id", leaseAgreementId,
                        "statusId", agreement.getString("statusId")), locale);
            }
            Timestamp after = m.getTimestamp("createdStamp");
            List<EntityCondition> billed = UtilMisc.toList(EntityCondition.makeCondition("leaseAgreementId", leaseAgreementId),
                    EntityCondition.makeCondition("installmentNumber", EntityOperator.GREATER_THAN_EQUAL_TO,
                            m.getLong("effectiveInstallmentNumber")));
            if (EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                    .where(EntityCondition.makeCondition(billed, EntityOperator.AND)).queryCount() > 0) {
                return blocked("billing", locale);
            }
            if (FinanceReversalHelper.countCreatedAfter(delegator, "LeaseProfitRecognition", after,
                    "leaseAgreementId", leaseAgreementId) > 0) {
                return blocked("deferred profit release", locale);
            }
            if (FinanceReversalHelper.countCreatedAfter(delegator, "LeaseAssetDepreciation", after,
                    "leaseAgreementId", leaseAgreementId) > 0) {
                return blocked("depreciation", locale);
            }
            if (countNot(delegator, "LeaseReLease", "statusId", LeaseReLeaseServices.STATUS_CANCELLED,
                    "leaseAgreementId", leaseAgreementId) > 0
                    || LeaseTerminationServices.findTermination(delegator, leaseAgreementId) != null) {
                return blocked("re-lease / termination", locale);
            }

            Timestamp now = UtilDateTime.nowTimestamp();
            List<String> reversing = new ArrayList<>();
            reversing.add(FinanceGlHelper.reverseTransaction(delegator, dispatcher, m.getString("acctgTransId"),
                    "Reversal of lease change of terms " + leaseAgreementId, now, userLogin));
            BigDecimal totalTax = copyRevision(delegator, leaseAgreementId, m.getLong("fromRevisionNumber"), current + 1);

            Timestamp newEnd = agreement.getTimestamp("leaseEndDate");
            BigDecimal delta = nz(m.getBigDecimal("newRemainingLeaseAmount")).subtract(nz(m.getBigDecimal("oldRemainingLeaseAmount")));
            agreement.set("termMonths", m.get("oldTermMonths"));
            agreement.set("leaseEndDate", m.get("oldLeaseEndDate"));
            agreement.set("monthlyLeaseAmount", m.get("oldMonthlyLeaseAmount"));
            agreement.set("monthlyRateFactor", nz(m.getBigDecimal("oldMonthlyLeaseAmount"))
                    .divide(nz(agreement.getBigDecimal("acquisitionCost")), 6, RoundingMode.HALF_UP));
            agreement.set("calcInterestRate", m.get("oldCalcInterestRate"));
            agreement.set("totalLeaseAmount", nz(agreement.getBigDecimal("totalLeaseAmount")).subtract(delta));
            agreement.set("totalInterestAmount", nz(agreement.getBigDecimal("totalInterestAmount"))
                    .subtract(nz(m.getBigDecimal("interestAdjustment"))));
            agreement.set("totalTaxAmount", totalTax);
            agreement.store();
            if (newEnd != null && !newEnd.equals(m.getTimestamp("oldLeaseEndDate"))) {
                FinanceWorkEffortHelper.cancelAndUnlinkTask(delegator, dispatcher, contractId,
                        FinanceWorkEffortHelper.TYPE_EXPIRY_NOTICE, newEnd.toLocalDateTime().toLocalDate().toString(), userLogin);
            }
            String reversalId = FinanceReversalHelper.record(delegator, contractId, FinanceReversalHelper.TYPE_LEASE_MOD,
                    m.getString("leaseModificationId"), m.getTimestamp("modificationDate"), reason, reversing, null, userLogin);
            m.set("reversedDate", now);
            m.set("financeReversalId", reversalId);
            m.store();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("financeReversalId", reversalId);
            result.put("scheduleRevisionNumber", current + 1);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
