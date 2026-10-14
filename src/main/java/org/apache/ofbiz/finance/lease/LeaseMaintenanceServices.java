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
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.finance.common.FinanceContractHelper;
import org.apache.ofbiz.finance.common.FinanceGlHelper;
import org.apache.ofbiz.finance.common.FinanceWorkEffortHelper;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Maintenance lease (メンテナンスリース, STEP L11).
 *
 * <ul>
 * <li>Revenue: the maintenance fee is an L9 component (LSCMP_MAINTENANCE) that is
 *     always separate and credited at billing to the maintenance revenue account
 *     ({@link LeaseComponentSettings}).</li>
 * <li>Plan ({@link #createLeaseMaintPlan}): what (core ProductMaintType), how
 *     often (months) and by whom (provider) for an agreement asset.</li>
 * <li>Schedule ({@link #runLeaseMaintScheduleBatch}): the plan's due dates within
 *     {@code lease.maint.schedule.months} become a {@code LeaseMaintRecord}
 *     (business status), a core {@code FixedAssetMaint} and a WorkEffort task
 *     (FINWE_MAINTENANCE). Idempotent per plan x due date.</li>
 * <li>Result ({@link #completeLeaseMaintenance}): the provider's cost is booked
 *     with a core purchase invoice (Dr maintenance cost / Cr accounts payable); the
 *     record and FixedAssetMaint are completed and then the task.</li>
 * </ul>
 */
public final class LeaseMaintenanceServices {

    private static final String MODULE = LeaseMaintenanceServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    public static final String STATUS_SCHEDULED = "LSMNT_SCHEDULED";
    public static final String STATUS_COMPLETED = "LSMNT_COMPLETED";
    public static final String STATUS_CANCELLED = "LSMNT_CANCELLED";
    public static final String GL_MAINT_REVENUE = "LEASE_MAINT_REVENUE";
    public static final String GL_MAINT_COST = "LEASE_MAINT_COST";
    public static final String ITEM_MAINT_COST = "LEASE_MAINT_COST";
    public static final String COMPONENT_MAINTENANCE = "LSCMP_MAINTENANCE";
    public static final String DEFAULT_UNSCHEDULED_TYPE = "LEASE_REPAIR";
    private static final String AGR_ACTIVE = "LEASEAGR_ACTIVE";
    private static final String AGR_CONTRACTED = "LEASEAGR_CONTRACTED";
    private static final String AGR_COMPLETED = "LEASEAGR_COMPLETED";
    private static final String ASSET_LEASED = "LSAST_LEASED";
    private static final int MAX_OCCURRENCES = 1200;

    private LeaseMaintenanceServices() {
    }

    private static Map<String, Object> error(String key, Map<String, ? extends Object> args, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, key, args, locale));
    }

    private static void check(Map<String, Object> result) throws GenericServiceException {
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(result));
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Timestamp addMonths(Timestamp ts, int months) {
        return UtilDateTime.adjustTimestamp(ts, Calendar.MONTH, months);
    }

    private static GenericValue findAgreement(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseAgreement").where("leaseAgreementId", leaseAgreementId)
                .cache(false).queryOne();
    }

    private static GenericValue lockAgreement(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        GenericValue agreement = findAgreement(delegator, leaseAgreementId);
        if (agreement == null) {
            return null;
        }
        FinanceContractHelper.lock(delegator, agreement.getString("financeContractId"));
        return findAgreement(delegator, leaseAgreementId);
    }

    private static String triggerKey(String maintPlanSeqId, Timestamp scheduledDate, String recordId) {
        return maintPlanSeqId != null
                ? "PLAN" + maintPlanSeqId + "-" + scheduledDate.toLocalDateTime().toLocalDate()
                : "MAINT" + recordId;
    }

    static int scheduleMonths(Delegator delegator) {
        return Integer.parseInt(EntityUtilProperties.getPropertyValue("finance", "lease.maint.schedule.months",
                "2", delegator).trim());
    }

    /** Default look-ahead of the schedule batch (lease.maint.schedule.months), for screens. */
    public static int scheduleMonthsOf(Delegator delegator) {
        return scheduleMonths(delegator);
    }

    // ------------------------------------------------------------------
    // Plan
    // ------------------------------------------------------------------

    /** Register a maintenance plan of an agreement asset (CONTRACTED or ACTIVE). */
    public static Map<String, Object> createLeaseMaintPlan(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String assetSeqId = (String) context.get("assetSeqId");
        Long interval = (Long) context.get("intervalMonths");
        String productMaintTypeId = (String) context.get("productMaintTypeId");
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            String status = agreement.getString("statusId");
            if (!AGR_CONTRACTED.equals(status) && !AGR_ACTIVE.equals(status)) {
                return error("FinanceLeaseMaintRequiresOpenAgreement", UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                        "statusId", status), locale);
            }
            if (EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId).queryOne() == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreementAsset",
                        "id", leaseAgreementId + "/" + assetSeqId), locale);
            }
            if (interval == null || interval <= 0) {
                return error("FinanceLeaseMaintIntervalInvalid", UtilMisc.toMap("intervalMonths", interval), locale);
            }
            if (EntityQuery.use(delegator).from("ProductMaintType").where("productMaintTypeId", productMaintTypeId)
                    .cache().queryOne() == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "ProductMaintType", "id", productMaintTypeId), locale);
            }
            Timestamp firstDue = (Timestamp) context.get("firstDueDate");
            Timestamp start = agreement.getTimestamp("leaseStartDate");
            if (firstDue == null && AGR_ACTIVE.equals(status) && start != null) {
                // A plan added during the lease starts at the next interval date from today.
                Timestamp today = UtilDateTime.getDayStart(UtilDateTime.nowTimestamp());
                Timestamp d = addMonths(start, interval.intValue());
                for (int i = 0; d.before(today) && i < MAX_OCCURRENCES; i++) {
                    d = addMonths(d, interval.intValue());
                }
                firstDue = d;
            }
            long seq = EntityQuery.use(delegator).from("LeaseMaintPlan").where("leaseAgreementId", leaseAgreementId).queryCount() + 1;
            String maintPlanSeqId = String.format("%05d", seq);
            GenericValue plan = delegator.makeValue("LeaseMaintPlan");
            plan.set("leaseAgreementId", leaseAgreementId);
            plan.set("maintPlanSeqId", maintPlanSeqId);
            plan.set("assetSeqId", assetSeqId);
            plan.set("productMaintTypeId", productMaintTypeId);
            plan.set("intervalMonths", interval);
            plan.set("firstDueDate", firstDue == null ? null : UtilDateTime.getDayStart(firstDue));
            plan.set("providerPartyId", context.get("providerPartyId"));
            plan.set("estimatedCost", context.get("estimatedCost"));
            plan.set("description", context.get("description"));
            plan.set("createdDate", UtilDateTime.nowTimestamp());
            plan.create();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("maintPlanSeqId", maintPlanSeqId);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** End a plan: no schedule after thruDate; scheduled maintenances on or after it are cancelled. */
    public static Map<String, Object> endLeaseMaintPlan(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String maintPlanSeqId = (String) context.get("maintPlanSeqId");
        Timestamp thru = context.get("thruDate") != null
                ? UtilDateTime.getDayStart((Timestamp) context.get("thruDate"))
                : UtilDateTime.getDayStart(UtilDateTime.nowTimestamp());
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            GenericValue plan = EntityQuery.use(delegator).from("LeaseMaintPlan")
                    .where("leaseAgreementId", leaseAgreementId, "maintPlanSeqId", maintPlanSeqId).queryOne();
            if (agreement == null || plan == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseMaintPlan",
                        "id", leaseAgreementId + "/" + maintPlanSeqId), locale);
            }
            plan.set("thruDate", thru);
            plan.store();
            long cancelled = 0;
            for (GenericValue rec : EntityQuery.use(delegator).from("LeaseMaintRecord").where(EntityCondition.makeCondition(
                    UtilMisc.toList(EntityCondition.makeCondition("leaseAgreementId", leaseAgreementId),
                            EntityCondition.makeCondition("maintPlanSeqId", maintPlanSeqId),
                            EntityCondition.makeCondition("statusId", STATUS_SCHEDULED),
                            EntityCondition.makeCondition("scheduledDate", EntityOperator.GREATER_THAN_EQUAL_TO, thru))))
                    .queryList()) {
                cancel(delegator, dispatcher, agreement, rec, "Plan ended", userLogin);
                cancelled++;
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("cancelledCount", cancelled);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Schedule
    // ------------------------------------------------------------------

    /** Due dates of a plan up to {@code horizon} (inclusive), within the lease and the plan period. */
    static List<Timestamp> dueDates(GenericValue agreement, GenericValue plan, Timestamp horizon) {
        List<Timestamp> dates = new ArrayList<>();
        int interval = plan.getLong("intervalMonths").intValue();
        Timestamp start = agreement.getTimestamp("leaseStartDate");
        Timestamp end = agreement.getTimestamp("leaseEndDate");
        Timestamp thru = plan.getTimestamp("thruDate");
        Timestamp d = plan.getTimestamp("firstDueDate") != null ? plan.getTimestamp("firstDueDate")
                : (start == null ? null : addMonths(UtilDateTime.getDayStart(start), interval));
        for (int i = 0; d != null && i < MAX_OCCURRENCES; i++) {
            if (d.after(horizon) || (end != null && d.after(end)) || (thru != null && !d.before(thru))) {
                break;
            }
            dates.add(d);
            d = addMonths(d, interval);
        }
        return dates;
    }

    /** Create the record, the core FixedAssetMaint and the task of one maintenance. */
    private static GenericValue schedule(Delegator delegator, LocalDispatcher dispatcher, GenericValue agreement,
            GenericValue asset, String maintPlanSeqId, String productMaintTypeId, Long intervalMonths,
            Timestamp scheduledDate, String providerPartyId, String assigneePartyId, String comments, GenericValue userLogin)
            throws GenericEntityException, GenericServiceException {
        String leaseAgreementId = agreement.getString("leaseAgreementId");
        String recordId = delegator.getNextSeqId("LeaseMaintRecord");
        String fixedAssetId = asset.getString("fixedAssetId");
        GenericValue type = EntityQuery.use(delegator).from("ProductMaintType")
                .where("productMaintTypeId", productMaintTypeId).cache().queryOne();
        String typeName = type == null || type.getString("description") == null ? productMaintTypeId : type.getString("description");
        FinanceWorkEffortHelper.Task task = FinanceWorkEffortHelper.ensureTask(delegator, dispatcher,
                agreement.getString("financeContractId"), FinanceWorkEffortHelper.TYPE_MAINTENANCE,
                triggerKey(maintPlanSeqId, scheduledDate, recordId),
                "Maintenance " + leaseAgreementId + "/" + asset.getString("assetSeqId") + " " + typeName,
                typeName + " of " + asset.getString("assetName"), scheduledDate, scheduledDate,
                assigneePartyId, fixedAssetId, userLogin);
        String maintHistSeqId = delegator.getNextSeqId("FixedAssetMaint");
        GenericValue fam = delegator.makeValue("FixedAssetMaint");
        fam.set("fixedAssetId", fixedAssetId);
        fam.set("maintHistSeqId", maintHistSeqId);
        fam.set("statusId", "FAM_SCHEDULED");
        fam.set("productMaintTypeId", productMaintTypeId);
        fam.set("scheduleWorkEffortId", task.getWorkEffortId());
        if (intervalMonths != null) {
            fam.set("intervalQuantity", new BigDecimal(intervalMonths));
            fam.set("intervalUomId", "TF_mon");
        }
        fam.create();
        GenericValue rec = delegator.makeValue("LeaseMaintRecord");
        rec.set("leaseMaintRecordId", recordId);
        rec.set("leaseAgreementId", leaseAgreementId);
        rec.set("maintPlanSeqId", maintPlanSeqId);
        rec.set("assetSeqId", asset.getString("assetSeqId"));
        rec.set("fixedAssetId", fixedAssetId);
        rec.set("maintHistSeqId", maintHistSeqId);
        rec.set("workEffortId", task.getWorkEffortId());
        rec.set("productMaintTypeId", productMaintTypeId);
        rec.set("scheduledDate", scheduledDate);
        rec.set("statusId", STATUS_SCHEDULED);
        rec.set("providerPartyId", providerPartyId);
        rec.set("currencyUomId", agreement.getString("currencyUomId"));
        rec.set("comments", comments);
        rec.set("createdDate", UtilDateTime.nowTimestamp());
        rec.create();
        return rec;
    }

    /**
     * For ACTIVE leases with maintenance plans: create the scheduled maintenances
     * due up to {@code processingDate + monthsAhead} (default
     * {@code lease.maint.schedule.months}). Idempotent per plan x due date.
     */
    public static Map<String, Object> runLeaseMaintScheduleBatch(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp processingDate = context.get("processingDate") != null
                ? (Timestamp) context.get("processingDate") : UtilDateTime.nowTimestamp();
        int months = context.get("monthsAhead") != null ? ((Long) context.get("monthsAhead")).intValue() : scheduleMonths(delegator);
        String onlyAgreement = (String) context.get("leaseAgreementId");
        String assignee = (String) context.get("assigneePartyId");
        Timestamp horizon = UtilDateTime.getDayEnd(addMonths(processingDate, months));
        long processed = 0;
        long created = 0;
        long skipped = 0;
        try {
            List<String> agreementIds = new ArrayList<>();
            EntityQuery q = EntityQuery.use(delegator).from("LeaseMaintPlan").select("leaseAgreementId").distinct();
            if (UtilValidate.isNotEmpty(onlyAgreement)) {
                q.where("leaseAgreementId", onlyAgreement);
            }
            for (GenericValue p : q.queryList()) {
                agreementIds.add(p.getString("leaseAgreementId"));
            }
            for (String leaseAgreementId : agreementIds) {
                GenericValue agreement = findAgreement(delegator, leaseAgreementId);
                if (agreement == null || !AGR_ACTIVE.equals(agreement.getString("statusId"))) {
                    continue;
                }
                agreement = lockAgreement(delegator, leaseAgreementId);
                for (GenericValue plan : EntityQuery.use(delegator).from("LeaseMaintPlan")
                        .where("leaseAgreementId", leaseAgreementId).orderBy("maintPlanSeqId").queryList()) {
                    GenericValue asset = EntityQuery.use(delegator).from("LeaseAgreementAsset")
                            .where("leaseAgreementId", leaseAgreementId, "assetSeqId", plan.getString("assetSeqId")).queryOne();
                    if (asset == null || UtilValidate.isEmpty(asset.getString("fixedAssetId"))
                            || !ASSET_LEASED.equals(asset.getString("assetStatusId"))) {
                        skipped++;
                        continue;
                    }
                    for (Timestamp due : dueDates(agreement, plan, horizon)) {
                        if (EntityQuery.use(delegator).from("LeaseMaintRecord").where("leaseAgreementId", leaseAgreementId,
                                "maintPlanSeqId", plan.getString("maintPlanSeqId"), "scheduledDate", due).queryFirst() != null) {
                            continue;
                        }
                        schedule(delegator, dispatcher, agreement, asset, plan.getString("maintPlanSeqId"),
                                plan.getString("productMaintTypeId"), plan.getLong("intervalMonths"), due,
                                plan.getString("providerPartyId"), assignee, plan.getString("description"), userLogin);
                        created++;
                    }
                }
                processed++;
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("processedAgreementCount", processed);
            result.put("createdCount", created);
            result.put("skippedPlanCount", skipped);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /** Register an unscheduled maintenance (repair) of a leased asset of an ACTIVE agreement. */
    public static Map<String, Object> createLeaseMaintenance(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String assetSeqId = (String) context.get("assetSeqId");
        String productMaintTypeId = UtilValidate.isNotEmpty((String) context.get("productMaintTypeId"))
                ? (String) context.get("productMaintTypeId") : DEFAULT_UNSCHEDULED_TYPE;
        Timestamp scheduledDate = UtilDateTime.getDayStart(context.get("scheduledDate") != null
                ? (Timestamp) context.get("scheduledDate") : UtilDateTime.nowTimestamp());
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            if (!AGR_ACTIVE.equals(agreement.getString("statusId"))) {
                return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            GenericValue asset = EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId).queryOne();
            if (asset == null || UtilValidate.isEmpty(asset.getString("fixedAssetId"))) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreementAsset",
                        "id", leaseAgreementId + "/" + assetSeqId), locale);
            }
            if (EntityQuery.use(delegator).from("ProductMaintType").where("productMaintTypeId", productMaintTypeId)
                    .cache().queryOne() == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "ProductMaintType", "id", productMaintTypeId), locale);
            }
            GenericValue rec = schedule(delegator, dispatcher, agreement, asset, null, productMaintTypeId, null,
                    scheduledDate, (String) context.get("providerPartyId"), (String) context.get("assigneePartyId"),
                    (String) context.get("comments"), userLogin);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("leaseMaintRecordId", rec.getString("leaseMaintRecordId"));
            result.put("workEffortId", rec.getString("workEffortId"));
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    /**
     * Record a performed maintenance: the provider's cost (tax excluded) as a core
     * purchase invoice (Dr maintenance cost / Cr accounts payable) when positive;
     * the record and FixedAssetMaint are completed, then the task.
     */
    public static Map<String, Object> completeLeaseMaintenance(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String recordId = (String) context.get("leaseMaintRecordId");
        Timestamp completionDate = context.get("completionDate") != null
                ? (Timestamp) context.get("completionDate") : UtilDateTime.nowTimestamp();
        BigDecimal cost = nz((BigDecimal) context.get("costAmount"));
        try {
            GenericValue rec = EntityQuery.use(delegator).from("LeaseMaintRecord").where("leaseMaintRecordId", recordId).queryOne();
            if (rec == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseMaintRecord", "id", recordId), locale);
            }
            GenericValue agreement = lockAgreement(delegator, rec.getString("leaseAgreementId"));
            rec.refresh();
            if (!STATUS_SCHEDULED.equals(rec.getString("statusId"))) {
                return error("FinanceLeaseMaintNotScheduled", UtilMisc.toMap("leaseMaintRecordId", recordId,
                        "statusId", rec.getString("statusId")), locale);
            }
            String agrStatus = agreement.getString("statusId");
            if (!AGR_ACTIVE.equals(agrStatus) && !AGR_COMPLETED.equals(agrStatus)) {
                return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId",
                        agreement.getString("leaseAgreementId")), locale);
            }
            if (cost.signum() < 0) {
                return error("FinanceLeaseMaintCostInvalid", UtilMisc.toMap("costAmount", cost), locale);
            }
            String provider = UtilValidate.isNotEmpty((String) context.get("providerPartyId"))
                    ? (String) context.get("providerPartyId") : rec.getString("providerPartyId");
            String invoiceId = null;
            if (cost.signum() > 0) {
                if (UtilValidate.isEmpty(provider)) {
                    return error("FinanceLeaseMaintProviderRequired", UtilMisc.toMap("leaseMaintRecordId", recordId), locale);
                }
                String organizationPartyId = agreement.getString("organizationPartyId");
                String costGl = FinanceGlHelper.findGlAccountId(delegator, organizationPartyId, GL_MAINT_COST);
                if (costGl == null) {
                    return error("FinanceGlAccountMappingMissing", UtilMisc.toMap("organizationPartyId", organizationPartyId,
                            "glAccountTypeId", GL_MAINT_COST), locale);
                }
                check(dispatcher.runSync("ensurePartyRole", UtilMisc.toMap("partyId", provider,
                        "roleTypeId", "BILL_FROM_VENDOR", "userLogin", userLogin)));
                Map<String, Object> invInput = new HashMap<>();
                invInput.put("invoiceTypeId", "PURCHASE_INVOICE");
                invInput.put("partyIdFrom", provider);
                invInput.put("partyId", organizationPartyId);
                invInput.put("statusId", "INVOICE_IN_PROCESS");
                invInput.put("currencyUomId", agreement.getString("currencyUomId"));
                invInput.put("invoiceDate", completionDate);
                invInput.put("referenceNumber", context.get("supplierInvoiceRef"));
                invInput.put("description", "Lease maintenance " + agreement.getString("leaseAgreementId") + "/"
                        + rec.getString("assetSeqId") + " " + recordId);
                invInput.put("userLogin", userLogin);
                Map<String, Object> inv = dispatcher.runSync("createInvoice", invInput);
                check(inv);
                invoiceId = (String) inv.get("invoiceId");
                Map<String, Object> item = new HashMap<>();
                item.put("invoiceId", invoiceId);
                item.put("invoiceItemTypeId", ITEM_MAINT_COST);
                item.put("quantity", BigDecimal.ONE);
                item.put("amount", cost);
                item.put("description", "Maintenance " + rec.getString("productMaintTypeId"));
                item.put("overrideGlAccountId", costGl);
                item.put("userLogin", userLogin);
                check(dispatcher.runSync("createInvoiceItem", item));
                check(dispatcher.runSync("setInvoiceStatus", UtilMisc.toMap("invoiceId", invoiceId,
                        "statusId", "INVOICE_READY", "userLogin", userLogin)));
                FinanceGlHelper.postUnposted(delegator, dispatcher, "invoiceId", invoiceId, userLogin);
            }
            rec.set("statusId", STATUS_COMPLETED);
            rec.set("completedDate", completionDate);
            rec.set("providerPartyId", provider);
            rec.set("costAmount", cost);
            rec.set("invoiceId", invoiceId);
            rec.set("supplierInvoiceRef", context.get("supplierInvoiceRef"));
            if (context.get("comments") != null) {
                rec.set("comments", context.get("comments"));
            }
            rec.store();
            setFamStatus(delegator, rec, "FAM_COMPLETED");
            FinanceWorkEffortHelper.completeTask(delegator, dispatcher, agreement.getString("financeContractId"),
                    FinanceWorkEffortHelper.TYPE_MAINTENANCE, triggerKey(rec.getString("maintPlanSeqId"),
                            rec.getTimestamp("scheduledDate"), recordId), completionDate, userLogin);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("invoiceId", invoiceId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static void setFamStatus(Delegator delegator, GenericValue rec, String statusId) throws GenericEntityException {
        GenericValue fam = EntityQuery.use(delegator).from("FixedAssetMaint").where("fixedAssetId", rec.getString("fixedAssetId"),
                "maintHistSeqId", rec.getString("maintHistSeqId")).queryOne();
        if (fam != null) {
            fam.set("statusId", statusId);
            fam.store();
        }
    }

    private static void cancel(Delegator delegator, LocalDispatcher dispatcher, GenericValue agreement, GenericValue rec,
            String comments, GenericValue userLogin) throws GenericEntityException, GenericServiceException {
        rec.set("statusId", STATUS_CANCELLED);
        if (comments != null) {
            rec.set("comments", comments);
        }
        rec.store();
        setFamStatus(delegator, rec, "FAM_CANCELLED");
        FinanceWorkEffortHelper.cancelTask(delegator, dispatcher, agreement.getString("financeContractId"),
                FinanceWorkEffortHelper.TYPE_MAINTENANCE, triggerKey(rec.getString("maintPlanSeqId"),
                        rec.getTimestamp("scheduledDate"), rec.getString("leaseMaintRecordId")), userLogin);
    }

    /** Cancel a scheduled maintenance (record, FixedAssetMaint and task). */
    public static Map<String, Object> cancelLeaseMaintenance(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String recordId = (String) context.get("leaseMaintRecordId");
        try {
            GenericValue rec = EntityQuery.use(delegator).from("LeaseMaintRecord").where("leaseMaintRecordId", recordId).queryOne();
            if (rec == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseMaintRecord", "id", recordId), locale);
            }
            GenericValue agreement = lockAgreement(delegator, rec.getString("leaseAgreementId"));
            rec.refresh();
            if (!STATUS_SCHEDULED.equals(rec.getString("statusId"))) {
                return error("FinanceLeaseMaintNotScheduled", UtilMisc.toMap("leaseMaintRecordId", recordId,
                        "statusId", rec.getString("statusId")), locale);
            }
            cancel(delegator, dctx.getDispatcher(), agreement, rec, (String) context.get("comments"),
                    (GenericValue) context.get("userLogin"));
            return ServiceUtil.returnSuccess();
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Summary
    // ------------------------------------------------------------------

    /**
     * Maintenance summary of an agreement: billed maintenance revenue (invoice items
     * credited to the maintenance revenue account), the contracted maintenance fee
     * (confirmed schedule), the cost of completed maintenances, the margin and the
     * record counts by status.
     */
    public static Map<String, Object> getLeaseMaintenanceSummary(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            String revenueGl = FinanceGlHelper.findGlAccountId(delegator, agreement.getString("organizationPartyId"),
                    LeaseComponentSettings.revenueAccountType(delegator, COMPONENT_MAINTENANCE));
            BigDecimal billed = BigDecimal.ZERO;
            if (revenueGl != null) {
                for (GenericValue link : EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                        .where("leaseAgreementId", leaseAgreementId).queryList()) {
                    for (GenericValue item : EntityQuery.use(delegator).from("InvoiceItem")
                            .where("invoiceId", link.getString("invoiceId"), "overrideGlAccountId", revenueGl).queryList()) {
                        billed = billed.add(nz(item.getBigDecimal("amount")).multiply(
                                item.getBigDecimal("quantity") == null ? BigDecimal.ONE : item.getBigDecimal("quantity")));
                    }
                }
            }
            BigDecimal contracted = BigDecimal.ZERO;
            for (GenericValue def : EntityQuery.use(delegator).from("LeaseAgreementComponent")
                    .where("leaseAgreementId", leaseAgreementId, "componentTypeEnumId", COMPONENT_MAINTENANCE).queryList()) {
                contracted = contracted.add(nz(def.getBigDecimal("totalAmount")));
            }
            BigDecimal cost = BigDecimal.ZERO;
            long scheduled = 0;
            long completed = 0;
            long cancelled = 0;
            for (GenericValue rec : EntityQuery.use(delegator).from("LeaseMaintRecord")
                    .where("leaseAgreementId", leaseAgreementId).queryList()) {
                String s = rec.getString("statusId");
                if (STATUS_COMPLETED.equals(s)) {
                    completed++;
                    cost = cost.add(nz(rec.getBigDecimal("costAmount")));
                } else if (STATUS_SCHEDULED.equals(s)) {
                    scheduled++;
                } else {
                    cancelled++;
                }
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("contractedMaintenanceAmount", contracted);
            result.put("billedMaintenanceRevenue", billed);
            result.put("maintenanceCost", cost);
            result.put("maintenanceMargin", billed.subtract(cost));
            result.put("scheduledCount", scheduled);
            result.put("completedCount", completed);
            result.put("cancelledCount", cancelled);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
