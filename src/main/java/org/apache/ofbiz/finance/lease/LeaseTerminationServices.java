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
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ofbiz.accounting.invoice.InvoiceWorker;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
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
 * Early termination of a lease (中途解約, STEP L14).
 *
 * <ul>
 * <li>Damages (規定損害金, {@link #calculateLeaseTerminationDamages}): the present
 *     value at the termination date of the lease payments due after it and not
 *     billed, discounted at the agreement's calculation interest rate / 12 over the
 *     (fractional) months to each due date; optionally the present value of the
 *     residual value ({@code lease.termination.include.residual}). Components and
 *     consumption tax are not part of it; the damages are not taxable (不課税).
 *     Billed but unpaid installments stay owed.</li>
 * <li>Termination ({@link #terminateLeaseAgreement}): bill the installments due
 *     through the termination date, account the lease through it (deferred profit /
 *     depreciation / re-lease), then:
 *     <ul>
 *     <li>finance lease: Dr deferred lease profit (remaining) / Cr lease investment;
 *         the damages invoice credits the lease investment / receivable; the
 *         difference to the residual value kept for the returned assets is the
 *         termination gain / loss;</li>
 *     <li>operating lease: the damages invoice credits termination income; the
 *         asset stays at its book value;</li>
 *     <li>re-lease: no damages; the unearned re-lease fees are recognized (no
 *         refund); contracted re-leases are cancelled.</li>
 *     </ul>
 *     The agreement becomes TERMINATED (billing stops), maintenance plans end, and
 *     an asset return task is created per asset. The return / disposal follow the
 *     expiry services; the agreement completes when the assets are back and every
 *     invoice is settled.</li>
 * </ul>
 */
public final class LeaseTerminationServices {

    private static final String MODULE = LeaseTerminationServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    public static final String STATUS_TERMINATED = "LEASEAGR_TERMINATED";
    public static final String ITEM_DAMAGES = "LEASE_TERM_DAMAGES";
    public static final String GL_TERM_INCOME = "LEASE_TERM_INCOME";
    public static final String GL_TERM_LOSS = "LEASE_TERM_LOSS";
    private static final MathContext MC = MathContext.DECIMAL64;

    private LeaseTerminationServices() {
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

    private static LocalDate day(Timestamp ts) {
        return ts.toLocalDateTime().toLocalDate();
    }

    private static GenericValue findAgreement(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseAgreement").where("leaseAgreementId", leaseAgreementId)
                .cache(false).queryOne();
    }

    /** True for an agreement still collected: ACTIVE, or TERMINATED (damages / assets being recovered). */
    public static boolean isCollectible(GenericValue agreement) {
        String s = agreement.getString("statusId");
        return "LEASEAGR_ACTIVE".equals(s) || STATUS_TERMINATED.equals(s);
    }

    public static boolean isTerminated(GenericValue agreement) {
        return agreement != null && STATUS_TERMINATED.equals(agreement.getString("statusId"));
    }

    public static GenericValue findTermination(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseTermination").where("leaseAgreementId", leaseAgreementId)
                .cache(false).queryOne();
    }

    /** Fractional months from {@code from} to {@code to} (whole months + remaining days / days of that month). */
    public static BigDecimal monthsBetween(LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) {
            return BigDecimal.ZERO;
        }
        long whole = ChronoUnit.MONTHS.between(from, to);
        LocalDate anchor = from.plusMonths(whole);
        long days = ChronoUnit.DAYS.between(anchor, to);
        return BigDecimal.valueOf(whole).add(BigDecimal.valueOf(days)
                .divide(BigDecimal.valueOf(anchor.lengthOfMonth()), 10, RoundingMode.HALF_UP));
    }

    /** Present value of {@code amount} due after {@code months} months at a monthly rate. */
    public static BigDecimal presentValue(BigDecimal amount, BigDecimal monthlyRate, BigDecimal months) {
        if (monthlyRate == null || monthlyRate.signum() <= 0 || months.signum() <= 0) {
            return amount;
        }
        double factor = Math.pow(1.0 + monthlyRate.doubleValue(), months.doubleValue());
        return amount.divide(new BigDecimal(factor, MC), MC);
    }

    /** The damages of an agreement terminated on {@code terminationDate} (no side effects). */
    public static Map<String, Object> computeDamages(Delegator delegator, GenericValue agreement, Timestamp terminationDate)
            throws GenericEntityException {
        String leaseAgreementId = agreement.getString("leaseAgreementId");
        String currency = agreement.getString("currencyUomId");
        int scale = FinanceInterestCalculator.resolveScale(currency, delegator);
        RoundingMode mode = FinanceInterestCalculator.resolveRoundingMode(currency, delegator);
        LocalDate term = day(terminationDate);
        BigDecimal annual = nz(agreement.getBigDecimal("calcInterestRate"));
        BigDecimal monthly = annual.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
        long revision = LeaseAccountingServices.currentScheduleRevision(delegator, leaseAgreementId);
        BigDecimal unbilled = BigDecimal.ZERO;
        BigDecimal pv = BigDecimal.ZERO;
        long count = 0;
        for (GenericValue row : EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                .where("leaseAgreementId", leaseAgreementId, "scheduleRevisionNumber", revision)
                .orderBy("installmentNumber").queryList()) {
            Timestamp due = row.getTimestamp("dueDate");
            if (due == null || !day(due).isAfter(term)) {
                continue;
            }
            if (EntityQuery.use(delegator).from("LeaseInstallmentInvoice").where("leaseAgreementId", leaseAgreementId,
                    "installmentNumber", row.getLong("installmentNumber")).queryCount() > 0) {
                continue;                                                       // billed: stays owed as billed
            }
            BigDecimal amount = nz(row.getBigDecimal("leaseAmount"));
            unbilled = unbilled.add(amount);
            pv = pv.add(presentValue(amount, monthly, monthsBetween(term, day(due))));
            count++;
        }
        boolean includeResidual = !LeaseProductHelper.isOperating(agreement) && "true".equalsIgnoreCase(
                EntityUtilProperties.getPropertyValue("finance", "lease.termination.include.residual", "false", delegator).trim());
        BigDecimal residual = nz(agreement.getBigDecimal("residualValue"));
        Timestamp end = agreement.getTimestamp("leaseEndDate");
        if (includeResidual && residual.signum() > 0 && end != null && day(end).isAfter(term)) {
            pv = pv.add(presentValue(residual, monthly, monthsBetween(term, day(end).plusDays(1))));
        }
        BigDecimal damages = pv.setScale(scale, mode);
        Map<String, Object> result = new HashMap<>();
        result.put("unbilledInstallmentCount", count);
        result.put("unbilledLeaseAmount", unbilled.setScale(scale, mode));
        result.put("monthlyDiscountRate", monthly);
        result.put("damagesAmount", damages);
        result.put("discountAmount", unbilled.setScale(scale, mode).subtract(damages).max(BigDecimal.ZERO));
        result.put("residualIncluded", includeResidual);
        return result;
    }

    private static String validate(Delegator delegator, GenericValue agreement, Timestamp terminationDate)
            throws GenericEntityException {
        if (!"LEASEAGR_ACTIVE".equals(agreement.getString("statusId"))) {
            return "FinanceLeaseAccountingRequiresActive";
        }
        LocalDate term = day(terminationDate);
        if (term.isBefore(day(agreement.getTimestamp("leaseStartDate")))
                || term.isAfter(day(UtilDateTime.nowTimestamp()))
                || term.isAfter(day(LeaseReLeaseServices.effectiveEndDate(delegator, agreement)))) {
            return "FinanceLeaseTerminationDateInvalid";
        }
        return null;
    }

    /** Service: the damages if the agreement were terminated on terminationDate (no change). */
    public static Map<String, Object> calculateLeaseTerminationDamages(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        Timestamp terminationDate = UtilDateTime.getDayStart(context.get("terminationDate") != null
                ? (Timestamp) context.get("terminationDate") : UtilDateTime.nowTimestamp());
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            String invalid = validate(delegator, agreement, terminationDate);
            if (invalid != null) {
                return error(invalid, UtilMisc.toMap("leaseAgreementId", leaseAgreementId, "terminationDate", terminationDate), locale);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.putAll(computeDamages(delegator, agreement, terminationDate));
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Termination
    // ------------------------------------------------------------------

    public static Map<String, Object> terminateLeaseAgreement(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        Timestamp terminationDate = UtilDateTime.getDayStart(context.get("terminationDate") != null
                ? (Timestamp) context.get("terminationDate") : UtilDateTime.nowTimestamp());
        try {
            GenericValue agreement = findAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            FinanceContractHelper.lock(delegator, agreement.getString("financeContractId"));
            GenericValue existing = findTermination(delegator, leaseAgreementId);
            if (existing != null) {
                return terminationResult(existing, false);
            }
            agreement = findAgreement(delegator, leaseAgreementId);
            String invalid = validate(delegator, agreement, terminationDate);
            if (invalid != null) {
                return error(invalid, UtilMisc.toMap("leaseAgreementId", leaseAgreementId, "terminationDate", terminationDate), locale);
            }
            String organizationPartyId = agreement.getString("organizationPartyId");
            String lessee = agreement.getString("lesseePartyId");
            String currency = agreement.getString("currencyUomId");
            boolean operating = LeaseProductHelper.isOperating(agreement);
            Timestamp leaseEnd = agreement.getTimestamp("leaseEndDate");
            boolean inReLease = day(terminationDate).isAfter(day(leaseEnd));
            Timestamp termEnd = UtilDateTime.getDayEnd(terminationDate);

            // 1. Account the lease through the termination date.
            long revision = LeaseAccountingServices.currentScheduleRevision(delegator, leaseAgreementId);
            for (GenericValue row : EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                    .where("leaseAgreementId", leaseAgreementId, "scheduleRevisionNumber", revision)
                    .orderBy("dueDate", "installmentNumber").queryList()) {
                if (row.getTimestamp("dueDate") != null && !row.getTimestamp("dueDate").after(termEnd)) {
                    check(LeaseAccountingServices.createLeaseInstallmentInvoice(dctx, UtilMisc.toMap("leaseAgreementId",
                            leaseAgreementId, "installmentNumber", row.getLong("installmentNumber"),
                            "userLogin", userLogin, "locale", locale)));
                }
            }
            check(LeaseAccountingServices.recognizeLeaseProfit(dctx, UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                    "recognitionDate", inReLease ? leaseEnd : terminationDate, "userLogin", userLogin, "locale", locale)));
            if (operating) {
                check(LeaseDepreciationServices.depreciateLeaseAssets(dctx, UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                        "depreciationDate", inReLease ? leaseEnd : terminationDate, "userLogin", userLogin, "locale", locale)));
            }
            LeaseReLeaseServices.processReLeases(dctx, findAgreement(delegator, leaseAgreementId), terminationDate, userLogin);
            agreement = findAgreement(delegator, leaseAgreementId);

            // 2. Damages (none during a re-lease: every lease payment is billed).
            Map<String, Object> calc = computeDamages(delegator, agreement, terminationDate);
            BigDecimal damages = (BigDecimal) calc.get("damagesAmount");
            boolean includeResidual = Boolean.TRUE.equals(calc.get("residualIncluded"));

            GenericValue t = delegator.makeValue("LeaseTermination");
            t.set("leaseAgreementId", leaseAgreementId);
            t.set("terminationDate", terminationDate);
            t.set("reason", context.get("reason"));
            t.set("unbilledInstallmentCount", calc.get("unbilledInstallmentCount"));
            t.set("unbilledLeaseAmount", calc.get("unbilledLeaseAmount"));
            t.set("monthlyDiscountRate", calc.get("monthlyDiscountRate"));
            t.set("discountAmount", calc.get("discountAmount"));
            t.set("residualIncluded", includeResidual ? "Y" : "N");
            t.set("damagesAmount", damages);
            t.set("currencyUomId", currency);

            // 3. Finance lease during the term: settle the lease investment.
            String damagesCreditType = GL_TERM_INCOME;
            if (!operating && !inReLease) {
                BigDecimal totalLease = nz(agreement.getBigDecimal("totalLeaseAmount"));
                BigDecimal billedLease = BigDecimal.ZERO;
                for (GenericValue link : EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                        .where("leaseAgreementId", leaseAgreementId).queryList()) {
                    billedLease = billedLease.add(nz(link.getBigDecimal("leaseAmount")));
                }
                BigDecimal recognized = BigDecimal.ZERO;
                for (GenericValue rec : EntityQuery.use(delegator).from("LeaseProfitRecognition")
                        .where("leaseAgreementId", leaseAgreementId).queryList()) {
                    recognized = recognized.add(nz(rec.getBigDecimal("amount")));
                }
                BigDecimal residual = nz(agreement.getBigDecimal("residualValue"));
                BigDecimal deferred = nz(agreement.getBigDecimal("totalInterestAmount")).subtract(recognized).max(BigDecimal.ZERO);
                BigDecimal net = totalLease.subtract(billedLease).add(residual).subtract(deferred);
                BigDecimal carried = includeResidual ? BigDecimal.ZERO : residual;
                BigDecimal gain = damages.add(carried).subtract(net);          // positive = gain
                String asset = LeaseRevenueStrategy.assetAccountType(agreement);
                List<FinanceGlHelper.Line> lines = new ArrayList<>();
                lines.add(new FinanceGlHelper.Line(LeaseRevenueStrategy.GL_DEFERRED_PROFIT, true, deferred));
                lines.add(new FinanceGlHelper.Line(asset, false, deferred));
                if (gain.signum() > 0) {
                    lines.add(new FinanceGlHelper.Line(asset, true, gain));
                    lines.add(new FinanceGlHelper.Line(GL_TERM_INCOME, false, gain));
                } else if (gain.signum() < 0) {
                    lines.add(new FinanceGlHelper.Line(GL_TERM_LOSS, true, gain.negate()));
                    lines.add(new FinanceGlHelper.Line(asset, false, gain.negate()));
                }
                t.set("acctgTransId", FinanceGlHelper.postTransaction(delegator, dispatcher,
                        "Lease termination " + leaseAgreementId, terminationDate, organizationPartyId, lessee, currency,
                        lines, userLogin));
                t.set("remainingDeferredProfit", deferred);
                t.set("netInvestment", net);
                t.set("residualCarried", carried);
                t.set("gainLossAmount", gain);
                damagesCreditType = asset;                                     // the damages replace the lease receivable
            } else if (operating) {
                t.set("gainLossAmount", damages);
            }

            // 4. Re-lease: no refund; the unearned fees are recognized; contracted periods cancelled.
            t.set("reLeaseRecognizedAmount", LeaseReLeaseServices.terminateReLeases(dctx, agreement, terminationDate, userLogin));

            // 5. Damages invoice (不課税).
            if (damages.signum() > 0) {
                t.set("invoiceId", damagesInvoice(dctx, agreement, damages, damagesCreditType, terminationDate, userLogin));
            }
            t.set("createdByUserLogin", userLogin == null ? null : userLogin.getString("userLoginId"));
            t.set("createdDate", UtilDateTime.nowTimestamp());
            t.create();

            // 6. Status (billing stops; the common contract stays ACTIVE while collected).
            agreement.set("statusId", STATUS_TERMINATED);
            agreement.store();
            // STEP L15c: advance lease payments left are applied to the damages invoice.
            LeaseAdvanceServices.applyAdvances(dctx, agreement, userLogin);

            // 7. Maintenance plans end; an asset recovery (return) task per asset.
            Timestamp nextDay = UtilDateTime.adjustTimestamp(terminationDate, Calendar.DAY_OF_MONTH, 1);
            for (GenericValue plan : EntityQuery.use(delegator).from("LeaseMaintPlan")
                    .where("leaseAgreementId", leaseAgreementId, "thruDate", null).queryList()) {
                check(dispatcher.runSync("endLeaseMaintPlan", UtilMisc.toMap("leaseAgreementId", leaseAgreementId,
                        "maintPlanSeqId", plan.getString("maintPlanSeqId"), "thruDate", nextDay, "userLogin", userLogin)));
            }
            for (GenericValue asset : EntityQuery.use(delegator).from("LeaseAgreementAsset")
                    .where("leaseAgreementId", leaseAgreementId, "assetStatusId", "LSAST_LEASED").queryList()) {
                FinanceWorkEffortHelper.ensureTask(delegator, dispatcher, agreement.getString("financeContractId"),
                        FinanceWorkEffortHelper.TYPE_ASSET_RETURN, asset.getString("assetSeqId"),
                        "Asset recovery " + leaseAgreementId + "/" + asset.getString("assetSeqId"),
                        "Recover " + asset.getString("assetName") + " from the lessee (early termination)", terminationDate,
                        null, (String) context.get("assigneePartyId"), asset.getString("fixedAssetId"), userLogin);
            }
            return terminationResult(t, true);
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    private static Map<String, Object> terminationResult(GenericValue t, boolean created) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("damagesAmount", t.getBigDecimal("damagesAmount"));
        result.put("invoiceId", t.getString("invoiceId"));
        result.put("acctgTransId", t.getString("acctgTransId"));
        result.put("gainLossAmount", t.getBigDecimal("gainLossAmount"));
        result.put("terminationCreated", created);
        return result;
    }

    private static String damagesInvoice(DispatchContext dctx, GenericValue agreement, BigDecimal damages,
            String creditGlAccountType, Timestamp terminationDate, GenericValue userLogin)
            throws GenericEntityException, GenericServiceException {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        String organizationPartyId = agreement.getString("organizationPartyId");
        String glAccountId = FinanceGlHelper.findGlAccountId(delegator, organizationPartyId, creditGlAccountType);
        if (glAccountId == null) {
            throw new GenericServiceException("No GL account of type " + creditGlAccountType
                    + " is configured for organization " + organizationPartyId);
        }
        int dueDays = Integer.parseInt(EntityUtilProperties.getPropertyValue("finance", "lease.termination.due.days", "0",
                delegator).trim());
        Map<String, Object> invInput = new HashMap<>();
        invInput.put("invoiceTypeId", "SALES_INVOICE");
        invInput.put("partyIdFrom", organizationPartyId);
        invInput.put("partyId", agreement.getString("lesseePartyId"));
        invInput.put("statusId", "INVOICE_IN_PROCESS");
        invInput.put("currencyUomId", agreement.getString("currencyUomId"));
        invInput.put("invoiceDate", UtilDateTime.nowTimestamp());
        invInput.put("dueDate", UtilDateTime.adjustTimestamp(terminationDate, Calendar.DAY_OF_MONTH, dueDays));
        invInput.put("description", "Lease termination damages " + agreement.getString("leaseAgreementId"));
        invInput.put("userLogin", userLogin);
        Map<String, Object> inv = dispatcher.runSync("createInvoice", invInput);
        check(inv);
        String invoiceId = (String) inv.get("invoiceId");
        Map<String, Object> item = new HashMap<>();
        item.put("invoiceId", invoiceId);
        item.put("invoiceItemTypeId", ITEM_DAMAGES);
        item.put("quantity", BigDecimal.ONE);
        item.put("amount", damages);
        item.put("description", "Termination damages (規定損害金)");
        item.put("overrideGlAccountId", glAccountId);
        item.put("userLogin", userLogin);
        check(dispatcher.runSync("createInvoiceItem", item));
        check(dispatcher.runSync("setInvoiceStatus", UtilMisc.toMap("invoiceId", invoiceId,
                "statusId", "INVOICE_READY", "userLogin", userLogin)));
        FinanceGlHelper.postUnposted(delegator, dispatcher, "invoiceId", invoiceId, userLogin);
        return invoiceId;
    }

    // ------------------------------------------------------------------
    // Collection support
    // ------------------------------------------------------------------

    /** The damages invoice as [invoiceId, dueDate], or null. */
    public static Object[] damagesInvoice(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        GenericValue t = findTermination(delegator, leaseAgreementId);
        if (t == null || UtilValidate.isEmpty(t.getString("invoiceId"))) {
            return null;
        }
        GenericValue invoice = EntityQuery.use(delegator).from("Invoice").where("invoiceId", t.getString("invoiceId"))
                .cache(false).queryOne();
        return new Object[] {t.getString("invoiceId"), invoice == null ? t.getTimestamp("terminationDate")
                : invoice.getTimestamp("dueDate")};
    }

    /** Damages figures for the collection status. */
    public static Map<String, BigDecimal> balance(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        GenericValue t = findTermination(delegator, leaseAgreementId);
        BigDecimal damages = t == null ? BigDecimal.ZERO : nz(t.getBigDecimal("damagesAmount"));
        BigDecimal applied = BigDecimal.ZERO;
        if (t != null && UtilValidate.isNotEmpty(t.getString("invoiceId"))) {
            applied = LeaseReLeaseServices.applied(delegator, t.getString("invoiceId"));
        }
        Map<String, BigDecimal> result = new HashMap<>();
        result.put("terminationDamagesAmount", damages);
        result.put("collectedTerminationAmount", applied.min(damages));
        return result;
    }

    /** The unpaid damages invoice as an overdue row of the lease delinquency (none when not due / settled). */
    public static List<Map<String, Object>> overdue(Delegator delegator, GenericValue agreement, Timestamp asOfDate)
            throws GenericEntityException {
        List<Map<String, Object>> rows = new ArrayList<>();
        Object[] inv = damagesInvoice(delegator, agreement.getString("leaseAgreementId"));
        if (inv == null || ((Timestamp) inv[1]).after(asOfDate)) {
            return rows;
        }
        GenericValue invoice = EntityQuery.use(delegator).from("Invoice").where("invoiceId", inv[0]).cache(false).queryOne();
        if (invoice == null || "INVOICE_PAID".equals(invoice.getString("statusId"))
                || "INVOICE_WRITEOFF".equals(invoice.getString("statusId"))
                || "INVOICE_CANCELLED".equals(invoice.getString("statusId"))) {
            return rows;
        }
        BigDecimal unpaid = nz(InvoiceWorker.getInvoiceNotApplied(invoice));
        if (unpaid.signum() <= 0) {
            return rows;
        }
        boolean operating = LeaseProductHelper.isOperating(agreement);
        long number = agreement.getLong("termMonths") + 1000L;               // after every installment / re-lease
        rows.add(UtilMisc.toMap("installmentNumber", number, "dueDate", inv[1], "invoiceId", inv[0], "unpaidAmount", unpaid,
                "principalAmount", operating ? BigDecimal.ZERO : unpaid, "interestAmount", operating ? unpaid : BigDecimal.ZERO,
                "componentAmount", BigDecimal.ZERO, "taxAmount", BigDecimal.ZERO, "leaseAmount", unpaid));
        return rows;
    }
}
