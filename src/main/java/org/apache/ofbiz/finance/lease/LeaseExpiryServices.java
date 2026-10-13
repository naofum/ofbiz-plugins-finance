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
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.apache.ofbiz.finance.common.FinanceInterestCalculator;
import org.apache.ofbiz.finance.common.FinanceWorkEffortHelper;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Lease expiry (STEP L7): expiry list and tasks, expiry notice, return and disposal
 * of the assets (no ownership transfer), ownership transfer (transfer leases) and
 * the completion of the agreement.
 *
 * <ul>
 * <li>The residual value of the agreement (booked in the lease investment / receivable
 *     at the start) is allocated to the assets by acquisition cost and settled at the
 *     disposal / transfer: Dr disposal receivable (proceeds) / Cr lease investment or
 *     receivable (residual), the difference to gain or loss on disposal.</li>
 * <li>Completion (LEASEAGR_COMPLETED, only here): every asset returned / disposed /
 *     transferred, every installment billed and every lease invoice settled; the
 *     deferred lease profit is released through the lease end first.</li>
 * <li>Human work is tracked as tasks (common {@link FinanceWorkEffortHelper}); the
 *     services below complete them, the tasks never change a business status.</li>
 * </ul>
 */
public final class LeaseExpiryServices {

    private static final String MODULE = LeaseExpiryServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    public static final String ASSET_LEASED = "LSAST_LEASED";
    public static final String ASSET_RETURNED = "LSAST_RETURNED";
    public static final String ASSET_TRANSFERRED = "LSAST_TRANSFERRED";
    public static final String ASSET_DISPOSED = "LSAST_DISPOSED";
    public static final String DISPOSAL_SALE = "LEASE_DISP_SALE";
    public static final String DISPOSAL_SCRAP = "LEASE_DISP_SCRAP";
    public static final String GL_DISPOSAL_RECV = "LEASE_DISPOSAL_RECV";
    public static final String GL_DISPOSAL_GAIN = "LEASE_DISPOSAL_GAIN";
    public static final String GL_DISPOSAL_LOSS = "LEASE_DISPOSAL_LOSS";
    public static final String TRIGGER_TRANSFER = "TRANSFER";

    private LeaseExpiryServices() {
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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

    private static LocalDate day(Timestamp ts) {
        return ts.toLocalDateTime().toLocalDate();
    }

    private static Timestamp dayStart(Timestamp ts) {
        return UtilDateTime.getDayStart(ts);
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

    private static boolean isTransfer(GenericValue agreement) {
        return LeaseProductHelper.CLASS_FIN_TRANSFER.equals(agreement.getString("leaseClassEnumId"));
    }

    private static List<GenericValue> assets(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseAgreementAsset").where("leaseAgreementId", leaseAgreementId)
                .orderBy("assetSeqId").cache(false).queryList();
    }

    /** Agreement residual value allocated to each asset by acquisition cost (last asset absorbs rounding). */
    public static Map<String, BigDecimal> allocateResidual(Delegator delegator, GenericValue agreement, BigDecimal total)
            throws GenericEntityException {
        List<GenericValue> lines = assets(delegator, agreement.getString("leaseAgreementId"));
        String currency = agreement.getString("currencyUomId");
        int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
        RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
        BigDecimal cost = BigDecimal.ZERO;
        for (GenericValue line : lines) {
            cost = cost.add(nz(line.getBigDecimal("acquisitionCost")));
        }
        Map<String, BigDecimal> result = new HashMap<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            GenericValue line = lines.get(i);
            BigDecimal share;
            if (i == lines.size() - 1) {
                share = nz(total).subtract(allocated);
            } else if (cost.signum() == 0) {
                share = BigDecimal.ZERO;
            } else {
                share = nz(total).multiply(nz(line.getBigDecimal("acquisitionCost"))).divide(cost, scale, mode);
            }
            allocated = allocated.add(share);
            result.put(line.getString("assetSeqId"), share);
        }
        return result;
    }

    /** Every installment billed and every lease invoice settled (paid / written off / cancelled). */
    public static boolean allBilledAndSettled(Delegator delegator, GenericValue agreement) throws GenericEntityException {
        String leaseAgreementId = agreement.getString("leaseAgreementId");
        long installments = EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                .where("leaseAgreementId", leaseAgreementId,
                        "scheduleRevisionNumber", LeaseAccountingServices.currentScheduleRevision(delegator, leaseAgreementId))
                .queryCount();
        List<GenericValue> links = EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                .where("leaseAgreementId", leaseAgreementId).queryList();
        if (links.size() < installments) {
            return false;
        }
        return new LeaseContractAdapter().findOpenInvoiceIds(delegator, agreement).isEmpty();
    }

    /** Last due date of the current schedule. */
    private static Timestamp lastDueDate(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        GenericValue last = EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                .where("leaseAgreementId", leaseAgreementId,
                        "scheduleRevisionNumber", LeaseAccountingServices.currentScheduleRevision(delegator, leaseAgreementId))
                .orderBy("-dueDate").queryFirst();
        return last == null ? null : last.getTimestamp("dueDate");
    }

    /** ACTIVE leases ending on or before {@code asOf} + {@code monthsAhead} months, ordered by end date. */
    public static List<GenericValue> findExpiring(Delegator delegator, Timestamp asOf, int monthsAhead)
            throws GenericEntityException {
        Timestamp limit = UtilDateTime.getDayEnd(UtilDateTime.adjustTimestamp(asOf, java.util.Calendar.MONTH, monthsAhead));
        return EntityQuery.use(delegator).from("LeaseAgreement")
                .where(EntityCondition.makeCondition(UtilMisc.toList(
                        EntityCondition.makeCondition("statusId", EntityOperator.EQUALS, "LEASEAGR_ACTIVE"),
                        EntityCondition.makeCondition("leaseEndDate", EntityOperator.LESS_THAN_EQUAL_TO, limit)),
                        EntityOperator.AND))
                .orderBy("leaseEndDate", "leaseAgreementId").cache(false).queryList();
    }

    public static int noticeMonths(Delegator delegator) {
        String value = EntityUtilProperties.getPropertyValue("finance", "lease.expiry.notice.months", "3", delegator).trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /**
     * GL settlement of the residual value of one asset: Dr disposal receivable (proceeds),
     * Cr lease investment / receivable (residual), Cr gain / Dr loss (difference).
     */
    private static String postSettlement(Delegator delegator, LocalDispatcher dispatcher, GenericValue agreement,
            String assetSeqId, BigDecimal residual, BigDecimal proceeds, Timestamp date, String partyId,
            GenericValue userLogin) throws GenericEntityException, GenericServiceException {
        List<FinanceGlHelper.Line> lines = new ArrayList<>();
        lines.add(new FinanceGlHelper.Line(GL_DISPOSAL_RECV, true, proceeds));
        lines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.assetAccountType(agreement), false, residual));
        BigDecimal diff = proceeds.subtract(residual);
        if (diff.signum() > 0) {
            lines.add(new FinanceGlHelper.Line(GL_DISPOSAL_GAIN, false, diff));
        } else if (diff.signum() < 0) {
            lines.add(new FinanceGlHelper.Line(GL_DISPOSAL_LOSS, true, diff.negate()));
        }
        return FinanceGlHelper.postTransaction(delegator, dispatcher,
                "Lease asset settlement " + agreement.getString("leaseAgreementId") + "/" + assetSeqId, date,
                agreement.getString("organizationPartyId"), partyId, agreement.getString("currencyUomId"), lines, userLogin);
    }

    private static void setFixedAssetEnd(Delegator delegator, GenericValue asset, Timestamp date, String newOwner)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(asset.getString("fixedAssetId"))) {
            return;
        }
        GenericValue fixedAsset = EntityQuery.use(delegator).from("FixedAsset")
                .where("fixedAssetId", asset.getString("fixedAssetId")).queryOne();
        if (fixedAsset == null) {
            return;
        }
        if (newOwner != null) {
            fixedAsset.set("partyId", newOwner);
        } else {
            fixedAsset.set("actualEndOfLife", new java.sql.Date(date.getTime()));
        }
        fixedAsset.store();
    }

    /**
     * Complete the agreement when every asset is returned / disposed / transferred and every
     * installment is billed and settled. Releases the deferred profit through the lease end first.
     *
     * @return null when completed, else the label key of the missing condition
     */
    private static String tryComplete(DispatchContext dctx, GenericValue agreement, Timestamp completionDate,
            GenericValue userLogin, Locale locale) throws GenericEntityException, GenericServiceException {
        Delegator delegator = dctx.getDelegator();
        String leaseAgreementId = agreement.getString("leaseAgreementId");
        for (GenericValue asset : assets(delegator, leaseAgreementId)) {
            String status = asset.getString("assetStatusId");
            if (!ASSET_RETURNED.equals(status) && !ASSET_DISPOSED.equals(status) && !ASSET_TRANSFERRED.equals(status)) {
                return "FinanceLeaseCompleteAssetsPending";
            }
        }
        if (!allBilledAndSettled(delegator, agreement)) {
            return "FinanceLeaseCompleteOpenBalance";
        }
        Map<String, Object> released = LeaseAccountingServices.recognizeLeaseProfit(dctx, UtilMisc.toMap(
                "leaseAgreementId", leaseAgreementId, "recognitionDate", agreement.getTimestamp("leaseEndDate"),
                "userLogin", userLogin, "locale", locale));
        check(released);
        GenericValue fresh = findAgreement(delegator, leaseAgreementId);
        fresh.set("statusId", "LEASEAGR_COMPLETED");
        fresh.set("completedDate", completionDate);
        fresh.store();
        FinanceContractHelper.updateStatus(delegator, fresh.getString("financeContractId"),
                FinanceContractHelper.STATUS_COMPLETED);
        return null;
    }

    private static Map<String, Object> completionResult(String missing) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("agreementCompleted", missing == null);
        return result;
    }

    // ------------------------------------------------------------------
    // Expiry batch: expiry list -> tasks
    // ------------------------------------------------------------------

    /**
     * For every ACTIVE lease ending within {@code monthsAhead} months (default
     * {@code lease.expiry.notice.months}): an expiry notice task; once the lease end is
     * reached, a return task per asset (no transfer) or, when every installment is
     * billed and settled, an ownership transfer task (transfer). Idempotent.
     */
    public static Map<String, Object> runLeaseExpiryBatch(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Timestamp processingDate = context.get("processingDate") != null
                ? (Timestamp) context.get("processingDate") : UtilDateTime.nowTimestamp();
        int months = context.get("monthsAhead") != null ? ((Long) context.get("monthsAhead")).intValue()
                : noticeMonths(delegator);
        String assignee = (String) context.get("assigneePartyId");
        long processed = 0;
        long created = 0;
        try {
            for (GenericValue listed : findExpiring(delegator, processingDate, months)) {
                GenericValue agreement = lockAgreement(delegator, listed.getString("leaseAgreementId"));
                String leaseAgreementId = agreement.getString("leaseAgreementId");
                String contractId = agreement.getString("financeContractId");
                Timestamp end = agreement.getTimestamp("leaseEndDate");
                String endKey = day(end).toString();
                if (FinanceWorkEffortHelper.ensureTask(delegator, dispatcher, contractId,
                        FinanceWorkEffortHelper.TYPE_EXPIRY_NOTICE, endKey, "Expiry notice " + leaseAgreementId,
                        "Send the expiry notice to the lessee (lease end " + endKey + ")", processingDate, end,
                        assignee, null, userLogin).isCreated()) {
                    created++;
                }
                if (!day(end).isAfter(day(processingDate))) {
                    if (isTransfer(agreement)) {
                        if (allBilledAndSettled(delegator, agreement) && FinanceWorkEffortHelper.ensureTask(delegator,
                                dispatcher, contractId, FinanceWorkEffortHelper.TYPE_OWNERSHIP_TRANSFER, TRIGGER_TRANSFER,
                                "Ownership transfer " + leaseAgreementId, "Transfer the ownership of the assets to the lessee",
                                processingDate, null, assignee, null, userLogin).isCreated()) {
                            created++;
                        }
                    } else {
                        for (GenericValue asset : assets(delegator, leaseAgreementId)) {
                            if (!ASSET_LEASED.equals(asset.getString("assetStatusId"))) {
                                continue;
                            }
                            if (FinanceWorkEffortHelper.ensureTask(delegator, dispatcher, contractId,
                                    FinanceWorkEffortHelper.TYPE_ASSET_RETURN, asset.getString("assetSeqId"),
                                    "Asset return " + leaseAgreementId + "/" + asset.getString("assetSeqId"),
                                    "Pick up " + asset.getString("assetName") + " from the lessee", processingDate, null,
                                    assignee, asset.getString("fixedAssetId"), userLogin).isCreated()) {
                                created++;
                            }
                        }
                    }
                }
                processed++;
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("processedAgreementCount", processed);
            result.put("createdTaskCount", created);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Expiry notice
    // ------------------------------------------------------------------

    /** Record the expiry notice of an ACTIVE lease (one per agreement) and complete its task. */
    public static Map<String, Object> issueLeaseExpiryNotice(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        Timestamp noticeDate = context.get("noticeDate") != null
                ? (Timestamp) context.get("noticeDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            GenericValue existing = EntityQuery.use(delegator).from("LeaseExpiryNotice")
                    .where("leaseAgreementId", leaseAgreementId).queryOne();
            if (existing == null) {
                if (!"LEASEAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                    return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
                }
                existing = delegator.makeValue("LeaseExpiryNotice");
                existing.set("leaseAgreementId", leaseAgreementId);
                existing.set("noticeDate", noticeDate);
                existing.set("leaseEndDate", agreement.getTimestamp("leaseEndDate"));
                existing.set("comments", context.get("comments"));
                existing.set("createdByUserLogin", userLogin == null ? null : userLogin.getString("userLoginId"));
                existing.set("createdDate", UtilDateTime.nowTimestamp());
                existing.create();
            }
            String workEffortId = FinanceWorkEffortHelper.completeTask(delegator, dctx.getDispatcher(),
                    agreement.getString("financeContractId"), FinanceWorkEffortHelper.TYPE_EXPIRY_NOTICE,
                    day(agreement.getTimestamp("leaseEndDate")).toString(), noticeDate, userLogin);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("noticeDate", existing.getTimestamp("noticeDate"));
            result.put("workEffortId", workEffortId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Return (no ownership transfer)
    // ------------------------------------------------------------------

    /**
     * Record the return of one asset of a lease without ownership transfer, on or after the
     * lease end. Completes the return task, creates the disposal task and completes the
     * agreement when every asset is back and every installment is settled.
     */
    public static Map<String, Object> returnLeaseAsset(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String assetSeqId = (String) context.get("assetSeqId");
        Timestamp returnDate = dayStart(context.get("returnDate") != null
                ? (Timestamp) context.get("returnDate") : UtilDateTime.nowTimestamp());
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            if (!"LEASEAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            if (isTransfer(agreement)) {
                return error("FinanceLeaseReturnNotForTransfer", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            if (day(returnDate).isBefore(day(agreement.getTimestamp("leaseEndDate")))) {
                return error("FinanceLeaseBeforeExpiry", UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                        "leaseEndDate", agreement.getTimestamp("leaseEndDate")), locale);
            }
            GenericValue asset = EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId).queryOne();
            if (asset == null || !ASSET_LEASED.equals(asset.getString("assetStatusId"))) {
                return error("FinanceLeaseAssetNotLeased", UtilMisc.toMap("assetSeqId", assetSeqId), locale);
            }
            BigDecimal residual = allocateResidual(delegator, agreement, agreement.getBigDecimal("residualValue")).get(assetSeqId);
            GenericValue settlement = delegator.makeValue("LeaseAssetSettlement");
            settlement.set("leaseAgreementId", leaseAgreementId);
            settlement.set("assetSeqId", assetSeqId);
            settlement.set("residualValue", residual);
            settlement.set("returnDate", returnDate);
            settlement.set("returnComments", context.get("comments"));
            settlement.set("createdDate", UtilDateTime.nowTimestamp());
            settlement.create();
            asset.set("assetStatusId", ASSET_RETURNED);
            asset.store();

            String contractId = agreement.getString("financeContractId");
            FinanceWorkEffortHelper.completeTask(delegator, dispatcher, contractId,
                    FinanceWorkEffortHelper.TYPE_ASSET_RETURN, assetSeqId, returnDate, userLogin);
            FinanceWorkEffortHelper.ensureTask(delegator, dispatcher, contractId,
                    FinanceWorkEffortHelper.TYPE_ASSET_DISPOSAL, assetSeqId,
                    "Asset disposal " + leaseAgreementId + "/" + assetSeqId,
                    "Sell or scrap the returned " + asset.getString("assetName"), returnDate, null,
                    (String) context.get("assigneePartyId"), asset.getString("fixedAssetId"), userLogin);
            return completionResult(tryComplete(dctx, agreement, returnDate, userLogin, locale));
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /**
     * Record the disposal (sale / scrap) of a returned asset and settle its residual value;
     * completes the disposal task. Allowed while ACTIVE or after completion.
     */
    public static Map<String, Object> disposeLeaseAsset(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String assetSeqId = (String) context.get("assetSeqId");
        String disposalType = (String) context.get("disposalTypeEnumId");
        BigDecimal proceeds = nz((BigDecimal) context.get("proceedsAmount"));
        Timestamp disposalDate = dayStart(context.get("disposalDate") != null
                ? (Timestamp) context.get("disposalDate") : UtilDateTime.nowTimestamp());
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            if (!DISPOSAL_SALE.equals(disposalType) && !DISPOSAL_SCRAP.equals(disposalType)) {
                return error("FinanceLeaseEnumInvalid", UtilMisc.toMap("field", "disposalTypeEnumId", "value", disposalType), locale);
            }
            if (proceeds.signum() < 0 || (DISPOSAL_SCRAP.equals(disposalType) && proceeds.signum() != 0)) {
                return error("FinanceLeaseDisposalProceedsInvalid", UtilMisc.toMap("proceedsAmount", proceeds), locale);
            }
            GenericValue asset = EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId).queryOne();
            GenericValue settlement = EntityQuery.use(delegator).from("LeaseAssetSettlement")
                    .where("leaseAgreementId", leaseAgreementId, "assetSeqId", assetSeqId).queryOne();
            if (asset == null || settlement == null || !ASSET_RETURNED.equals(asset.getString("assetStatusId"))) {
                return error("FinanceLeaseAssetNotReturned", UtilMisc.toMap("assetSeqId", assetSeqId), locale);
            }
            if (disposalDate.before(settlement.getTimestamp("returnDate"))) {
                return error("FinanceLeaseDisposalBeforeReturn", UtilMisc.toMap("assetSeqId", assetSeqId), locale);
            }
            BigDecimal residual = nz(settlement.getBigDecimal("residualValue"));
            String buyer = (String) context.get("disposalPartyId");
            String acctgTransId = postSettlement(delegator, dispatcher, agreement, assetSeqId, residual, proceeds,
                    disposalDate, UtilValidate.isNotEmpty(buyer) ? buyer : agreement.getString("lesseePartyId"), userLogin);
            settlement.set("disposalTypeEnumId", disposalType);
            settlement.set("disposalDate", disposalDate);
            settlement.set("disposalPartyId", buyer);
            settlement.set("proceedsAmount", proceeds);
            settlement.set("gainLossAmount", proceeds.subtract(residual));
            settlement.set("acctgTransId", acctgTransId);
            settlement.store();
            asset.set("assetStatusId", ASSET_DISPOSED);
            asset.store();
            setFixedAssetEnd(delegator, asset, disposalDate, null);
            String workEffortId = FinanceWorkEffortHelper.completeTask(delegator, dispatcher,
                    agreement.getString("financeContractId"), FinanceWorkEffortHelper.TYPE_ASSET_DISPOSAL, assetSeqId,
                    disposalDate, userLogin);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("acctgTransId", acctgTransId);
            result.put("gainLossAmount", proceeds.subtract(residual));
            result.put("workEffortId", workEffortId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Ownership transfer
    // ------------------------------------------------------------------

    /**
     * Transfer the ownership of every asset of a transfer lease to the lessee after the
     * final installment is paid (every installment billed and settled, transfer date on or
     * after the last due date). The transfer price (default: the residual value) settles the
     * residual; FixedAsset.partyId becomes the lessee; the agreement is completed.
     */
    public static Map<String, Object> transferLeaseOwnership(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        Timestamp transferDate = dayStart(context.get("transferDate") != null
                ? (Timestamp) context.get("transferDate") : UtilDateTime.nowTimestamp());
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            if (!"LEASEAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            if (!isTransfer(agreement)) {
                return error("FinanceLeaseTransferNotAllowed", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            Timestamp lastDue = lastDueDate(delegator, leaseAgreementId);
            if (!allBilledAndSettled(delegator, agreement)
                    || (lastDue != null && day(transferDate).isBefore(day(lastDue)))) {
                return error("FinanceLeaseTransferRequiresFinalPayment", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            BigDecimal residual = nz(agreement.getBigDecimal("residualValue"));
            BigDecimal price = context.get("transferPrice") != null ? (BigDecimal) context.get("transferPrice") : residual;
            if (price.signum() < 0) {
                return error("FinanceLeaseDisposalProceedsInvalid", UtilMisc.toMap("proceedsAmount", price), locale);
            }
            Map<String, BigDecimal> residuals = allocateResidual(delegator, agreement, residual);
            Map<String, BigDecimal> prices = allocateResidual(delegator, agreement, price);
            String lessee = agreement.getString("lesseePartyId");
            List<String> transIds = new ArrayList<>();
            for (GenericValue asset : assets(delegator, leaseAgreementId)) {
                String seq = asset.getString("assetSeqId");
                if (!ASSET_LEASED.equals(asset.getString("assetStatusId"))) {
                    return error("FinanceLeaseAssetNotLeased", UtilMisc.toMap("assetSeqId", seq), locale);
                }
                BigDecimal assetResidual = residuals.get(seq);
                BigDecimal assetPrice = prices.get(seq);
                String acctgTransId = postSettlement(delegator, dispatcher, agreement, seq, assetResidual, assetPrice,
                        transferDate, lessee, userLogin);
                if (acctgTransId != null) {
                    transIds.add(acctgTransId);
                }
                GenericValue settlement = delegator.makeValue("LeaseAssetSettlement");
                settlement.set("leaseAgreementId", leaseAgreementId);
                settlement.set("assetSeqId", seq);
                settlement.set("residualValue", assetResidual);
                settlement.set("transferDate", transferDate);
                settlement.set("disposalTypeEnumId", DISPOSAL_SALE);
                settlement.set("disposalDate", transferDate);
                settlement.set("disposalPartyId", lessee);
                settlement.set("proceedsAmount", assetPrice);
                settlement.set("gainLossAmount", assetPrice.subtract(assetResidual));
                settlement.set("acctgTransId", acctgTransId);
                settlement.set("createdDate", UtilDateTime.nowTimestamp());
                settlement.create();
                asset.set("assetStatusId", ASSET_TRANSFERRED);
                asset.store();
                setFixedAssetEnd(delegator, asset, transferDate, lessee);
            }
            FinanceWorkEffortHelper.completeTask(delegator, dispatcher, agreement.getString("financeContractId"),
                    FinanceWorkEffortHelper.TYPE_OWNERSHIP_TRANSFER, TRIGGER_TRANSFER, transferDate, userLogin);
            Map<String, Object> result = completionResult(tryComplete(dctx, agreement, transferDate, userLogin, locale));
            result.put("acctgTransIds", transIds);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Explicit completion (e.g. after the last payment arrives)
    // ------------------------------------------------------------------

    public static Map<String, Object> completeLeaseAgreement(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        Timestamp completionDate = dayStart(context.get("completionDate") != null
                ? (Timestamp) context.get("completionDate") : UtilDateTime.nowTimestamp());
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            if ("LEASEAGR_COMPLETED".equals(agreement.getString("statusId"))) {
                return completionResult(null);
            }
            if (!"LEASEAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                return error("FinanceLeaseAccountingRequiresActive", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            if (day(completionDate).isBefore(day(agreement.getTimestamp("leaseEndDate"))) && !isTransfer(agreement)) {
                return error("FinanceLeaseBeforeExpiry", UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                        "leaseEndDate", agreement.getTimestamp("leaseEndDate")), locale);
            }
            String missing = tryComplete(dctx, agreement, completionDate, userLogin, locale);
            if (missing != null) {
                return error(missing, UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            return completionResult(null);
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
