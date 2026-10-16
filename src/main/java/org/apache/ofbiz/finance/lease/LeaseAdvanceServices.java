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
import org.apache.ofbiz.finance.common.FinanceContractHelper;
import org.apache.ofbiz.finance.common.FinanceGlHelper;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * STEP L15c: advance lease payments (前受リース料) and security deposits (保証金),
 * {@code SPEC_LEASE.md} 19.
 *
 * <ul>
 * <li>Receipt: a core {@code CUSTOMER_DEPOSIT} payment (PMNT_RECEIVED) whose {@code overrideGlAccountId}
 *     is the liability, so the core posts Dr cash / Cr advance lease payments (224800) or
 *     security deposits (239500). One {@code LeaseAdvanceReceipt} per receipt.</li>
 * <li>Advance lease payments are applied automatically to the open lease invoices, oldest receipt
 *     and oldest invoice first: at the receipt, after every new installment / re-lease / damages
 *     invoice. The core application posts Dr advance lease payments / Cr accounts receivable.</li>
 * <li>Security deposits are not applied automatically. The settlement (after the return of every asset
 *     or a termination, or before a cancellation) applies the advance lease payments and then the
 *     deposits to the unpaid invoices, and refunds the rest with a core {@code CUSTOMER_REFUND}
 *     payment (PMNT_SENT, Dr liability / Cr cash) applied to the receipt payment.</li>
 * <li>The lease is completed only when no receipt has a balance left.</li>
 * </ul>
 * The source of truth of the applied amounts is the core {@code PaymentApplication}.
 */
public final class LeaseAdvanceServices {

    private static final String MODULE = LeaseAdvanceServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    public static final String TYPE_LEASE = "LSADV_LEASE";
    public static final String TYPE_DEPOSIT = "LSADV_DEPOSIT";
    public static final String STATUS_RECEIVED = "LSADVST_RECEIVED";
    public static final String STATUS_APPLIED = "LSADVST_APPLIED";
    public static final String STATUS_REFUNDED = "LSADVST_REFUNDED";
    public static final String GL_ADVANCE = "LEASE_ADVANCE_RCVD";
    public static final String GL_DEPOSIT = "LEASE_DEPOSIT_RCVD";

    private LeaseAdvanceServices() {
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

    private static String glAccountType(String advanceTypeEnumId) {
        return TYPE_DEPOSIT.equals(advanceTypeEnumId) ? GL_DEPOSIT : GL_ADVANCE;
    }

    /** Receipts of an agreement, oldest first (advance lease payments before deposits on the same day). */
    private static List<GenericValue> receipts(Delegator delegator, String leaseAgreementId, String advanceTypeEnumId)
            throws GenericEntityException {
        List<GenericValue> all = EntityQuery.use(delegator).from("LeaseAdvanceReceipt")
                .where("leaseAgreementId", leaseAgreementId).orderBy("receiptDate", "leaseAdvanceReceiptId").queryList();
        List<GenericValue> result = new ArrayList<>();
        for (GenericValue r : all) {
            if (advanceTypeEnumId == null || advanceTypeEnumId.equals(r.getString("advanceTypeEnumId"))) {
                result.add(r);
            }
        }
        return result;
    }

    /** Amount of the receipt applied to invoices (core payment applications of the receipt payment). */
    private static BigDecimal appliedToInvoices(Delegator delegator, GenericValue receipt) throws GenericEntityException {
        BigDecimal applied = BigDecimal.ZERO;
        for (GenericValue app : EntityQuery.use(delegator).from("PaymentApplication")
                .where("paymentId", receipt.getString("paymentId")).cache(false).queryList()) {
            if (UtilValidate.isNotEmpty(app.getString("invoiceId"))) {
                applied = applied.add(nz(app.getBigDecimal("amountApplied")));
            }
        }
        return applied;
    }

    /** Balance left of a receipt: amount - applied to invoices - refunded. */
    private static BigDecimal available(Delegator delegator, GenericValue receipt) throws GenericEntityException {
        return nz(receipt.getBigDecimal("amount")).subtract(appliedToInvoices(delegator, receipt))
                .subtract(nz(receipt.getBigDecimal("refundedAmount")));
    }

    /** {@code true} when a receipt of the agreement still has a balance (completion is not allowed). */
    public static boolean hasBalance(Delegator delegator, String leaseAgreementId) throws GenericEntityException {
        for (GenericValue r : receipts(delegator, leaseAgreementId, null)) {
            if (available(delegator, r).signum() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Apply the receipts of the given type (null = every type, advance lease payments first) to the
     * open lease invoices, oldest receipt and oldest invoice first. Returns the amount applied.
     */
    private static BigDecimal apply(DispatchContext dctx, GenericValue agreement, List<GenericValue> receipts,
            GenericValue userLogin) throws GenericEntityException, GenericServiceException {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        BigDecimal total = BigDecimal.ZERO;
        List<GenericValue> open = null;
        for (GenericValue receipt : receipts) {
            BigDecimal left = available(delegator, receipt);
            if (left.signum() <= 0) {
                continue;
            }
            if (open == null) {
                open = LeaseAccountingServices.openInvoices(delegator, agreement);
            }
            BigDecimal appliedNow = BigDecimal.ZERO;
            for (GenericValue invoice : open) {
                if (left.signum() <= 0) {
                    break;
                }
                BigDecimal notApplied = InvoiceWorker.getInvoiceNotApplied(invoice);
                if (notApplied.signum() <= 0) {
                    continue;
                }
                BigDecimal amount = left.min(notApplied);
                check(dispatcher.runSync("createPaymentApplication", UtilMisc.toMap("paymentId", receipt.getString("paymentId"),
                        "invoiceId", invoice.getString("invoiceId"), "amountApplied", amount, "userLogin", userLogin)));
                left = left.subtract(amount);
                appliedNow = appliedNow.add(amount);
                // re-read: the invoice may now be PAID and its open amount is lower
                invoice.refresh();
            }
            if (appliedNow.signum() > 0) {
                FinanceGlHelper.postUnposted(delegator, dispatcher, "paymentId", receipt.getString("paymentId"), userLogin);
                receipt.set("appliedAmount", appliedToInvoices(delegator, receipt));
                if (available(delegator, receipt).signum() <= 0) {
                    receipt.set("statusId", STATUS_APPLIED);
                    receipt.set("settledDate", UtilDateTime.nowTimestamp());
                }
                receipt.store();
                total = total.add(appliedNow);
            }
        }
        return total;
    }

    /**
     * Apply the advance lease payments of the agreement to its open invoices (called after the receipt
     * and after every new lease invoice, under the contract lock of the caller). Deposits are not used.
     */
    public static BigDecimal applyAdvances(DispatchContext dctx, GenericValue agreement, GenericValue userLogin)
            throws GenericEntityException, GenericServiceException {
        return apply(dctx, agreement, receipts(dctx.getDelegator(), agreement.getString("leaseAgreementId"), TYPE_LEASE),
                userLogin);
    }

    // ------------------------------------------------------------------
    // Services
    // ------------------------------------------------------------------

    /**
     * Receive an advance lease payment or a security deposit (agreement CONTRACTED or ACTIVE).
     * An advance lease payment is applied at once to the invoices already open.
     */
    public static Map<String, Object> receiveLeaseAdvance(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String type = (String) context.get("advanceTypeEnumId");
        BigDecimal amount = (BigDecimal) context.get("amount");
        String paymentMethodTypeId = UtilValidate.isNotEmpty((String) context.get("paymentMethodTypeId"))
                ? (String) context.get("paymentMethodTypeId") : "EFT_ACCOUNT";
        Timestamp receiptDate = context.get("receiptDate") != null
                ? (Timestamp) context.get("receiptDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            String status = agreement.getString("statusId");
            if (!"LEASEAGR_CONTRACTED".equals(status) && !"LEASEAGR_ACTIVE".equals(status)) {
                return error("FinanceLeaseAdvanceRequiresOpen", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            if (!TYPE_LEASE.equals(type) && !TYPE_DEPOSIT.equals(type)) {
                return error("FinanceLeaseAdvanceTypeInvalid", UtilMisc.toMap("advanceTypeEnumId", type), locale);
            }
            if (amount == null || amount.signum() <= 0) {
                return error("FinancePaymentAmountInvalid", UtilMisc.toMap(), locale);
            }
            String organizationPartyId = agreement.getString("organizationPartyId");
            String glAccountId = FinanceGlHelper.findGlAccountId(delegator, organizationPartyId, glAccountType(type));
            if (glAccountId == null) {
                throw new GenericServiceException("No GL account of type " + glAccountType(type)
                        + " is configured for organization " + organizationPartyId);
            }
            Map<String, Object> payInput = new HashMap<>();
            payInput.put("paymentTypeId", "CUSTOMER_DEPOSIT");
            payInput.put("partyIdFrom", agreement.getString("lesseePartyId"));
            payInput.put("partyIdTo", organizationPartyId);
            payInput.put("statusId", "PMNT_RECEIVED");
            payInput.put("paymentMethodTypeId", paymentMethodTypeId);
            payInput.put("amount", amount);
            payInput.put("currencyUomId", agreement.getString("currencyUomId"));
            payInput.put("effectiveDate", receiptDate);
            payInput.put("overrideGlAccountId", glAccountId);
            payInput.put("comments", (TYPE_DEPOSIT.equals(type) ? "Lease deposit " : "Lease advance ") + leaseAgreementId);
            payInput.put("userLogin", userLogin);
            Map<String, Object> pay = dispatcher.runSync("createPayment", payInput);
            check(pay);
            String paymentId = (String) pay.get("paymentId");
            FinanceGlHelper.postUnposted(delegator, dispatcher, "paymentId", paymentId, userLogin);

            String receiptId = delegator.getNextSeqId("LeaseAdvanceReceipt");
            GenericValue receipt = delegator.makeValue("LeaseAdvanceReceipt");
            receipt.set("leaseAdvanceReceiptId", receiptId);
            receipt.set("leaseAgreementId", leaseAgreementId);
            receipt.set("advanceTypeEnumId", type);
            receipt.set("statusId", STATUS_RECEIVED);
            receipt.set("receiptDate", receiptDate);
            receipt.set("amount", amount);
            receipt.set("currencyUomId", agreement.getString("currencyUomId"));
            receipt.set("paymentId", paymentId);
            receipt.set("appliedAmount", BigDecimal.ZERO);
            receipt.set("refundedAmount", BigDecimal.ZERO);
            receipt.set("comments", context.get("comments"));
            receipt.set("createdDate", UtilDateTime.nowTimestamp());
            receipt.create();

            BigDecimal applied = BigDecimal.ZERO;
            if (TYPE_LEASE.equals(type) && "LEASEAGR_ACTIVE".equals(status)) {
                applied = applyAdvances(dctx, agreement, userLogin);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("leaseAdvanceReceiptId", receiptId);
            result.put("paymentId", paymentId);
            result.put("appliedAmount", applied);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /**
     * Settle the advance lease payments and security deposits of an agreement: apply them to the
     * unpaid lease invoices (advance lease payments first, oldest first) and refund the rest.
     * Allowed when the agreement is CONTRACTED (refund before a cancellation), TERMINATED, or ACTIVE
     * with every installment billed and every asset returned / disposed / transferred. Then the lease
     * is completed when every other condition holds.
     */
    public static Map<String, Object> settleLeaseAdvances(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String leaseAgreementId = (String) context.get("leaseAgreementId");
        String paymentMethodTypeId = UtilValidate.isNotEmpty((String) context.get("paymentMethodTypeId"))
                ? (String) context.get("paymentMethodTypeId") : "EFT_ACCOUNT";
        Timestamp settlementDate = context.get("settlementDate") != null
                ? (Timestamp) context.get("settlementDate") : UtilDateTime.nowTimestamp();
        try {
            GenericValue agreement = lockAgreement(delegator, leaseAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LeaseAgreement", "id", leaseAgreementId), locale);
            }
            String status = agreement.getString("statusId");
            boolean contracted = "LEASEAGR_CONTRACTED".equals(status);
            boolean terminated = LeaseTerminationServices.isTerminated(agreement);
            boolean active = "LEASEAGR_ACTIVE".equals(status);
            if (!contracted && !terminated && !(active && endReached(delegator, agreement))) {
                return error("FinanceLeaseAdvanceSettleNotReady", UtilMisc.toMap("leaseAgreementId", leaseAgreementId), locale);
            }
            List<GenericValue> receipts = new ArrayList<>(receipts(delegator, leaseAgreementId, TYPE_LEASE));
            receipts.addAll(receipts(delegator, leaseAgreementId, TYPE_DEPOSIT));
            BigDecimal applied = contracted ? BigDecimal.ZERO : apply(dctx, agreement, receipts, userLogin);

            BigDecimal refunded = BigDecimal.ZERO;
            for (GenericValue receipt : receipts) {
                receipt.refresh();
                BigDecimal left = available(delegator, receipt);
                if (left.signum() <= 0) {
                    continue;
                }
                Map<String, Object> payInput = new HashMap<>();
                payInput.put("paymentTypeId", "CUSTOMER_REFUND");
                payInput.put("partyIdFrom", agreement.getString("organizationPartyId"));
                payInput.put("partyIdTo", agreement.getString("lesseePartyId"));
                payInput.put("statusId", "PMNT_SENT");
                payInput.put("paymentMethodTypeId", paymentMethodTypeId);
                payInput.put("amount", left);
                payInput.put("currencyUomId", receipt.getString("currencyUomId"));
                payInput.put("effectiveDate", settlementDate);
                payInput.put("overrideGlAccountId", FinanceGlHelper.findGlAccountId(delegator,
                        agreement.getString("organizationPartyId"), glAccountType(receipt.getString("advanceTypeEnumId"))));
                payInput.put("comments", "Lease advance refund " + leaseAgreementId + " / " + receipt.getString("leaseAdvanceReceiptId"));
                payInput.put("userLogin", userLogin);
                Map<String, Object> pay = dispatcher.runSync("createPayment", payInput);
                check(pay);
                String refundPaymentId = (String) pay.get("paymentId");
                // The refund uses the receipt (payment-to-payment application, no GL in the core).
                check(dispatcher.runSync("createPaymentApplication", UtilMisc.toMap("paymentId", refundPaymentId,
                        "toPaymentId", receipt.getString("paymentId"), "amountApplied", left, "userLogin", userLogin)));
                FinanceGlHelper.postUnposted(delegator, dispatcher, "paymentId", refundPaymentId, userLogin);
                receipt.set("refundedAmount", nz(receipt.getBigDecimal("refundedAmount")).add(left));
                receipt.set("refundPaymentId", refundPaymentId);
                receipt.set("appliedAmount", appliedToInvoices(delegator, receipt));
                receipt.set("statusId", STATUS_REFUNDED);
                receipt.set("settledDate", settlementDate);
                receipt.store();
                refunded = refunded.add(left);
            }

            boolean completed = false;
            if (!contracted) {
                GenericValue fresh = findAgreement(delegator, leaseAgreementId);
                completed = "LEASEAGR_COMPLETED".equals(fresh.getString("statusId"))
                        || (endReached(delegator, fresh) && LeaseExpiryServices.tryComplete(dctx, fresh, settlementDate,
                                userLogin, locale) == null);
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("appliedAmount", applied);
            result.put("refundedAmount", refunded);
            result.put("agreementCompleted", completed);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    /**
     * Every asset returned / disposed / transferred and, unless terminated, every installment of the
     * current schedule billed.
     */
    private static boolean endReached(Delegator delegator, GenericValue agreement) throws GenericEntityException {
        String leaseAgreementId = agreement.getString("leaseAgreementId");
        for (GenericValue asset : EntityQuery.use(delegator).from("LeaseAgreementAsset")
                .where("leaseAgreementId", leaseAgreementId).queryList()) {
            String s = asset.getString("assetStatusId");
            if (!LeaseExpiryServices.ASSET_RETURNED.equals(s) && !LeaseExpiryServices.ASSET_DISPOSED.equals(s)
                    && !LeaseExpiryServices.ASSET_TRANSFERRED.equals(s)) {
                return false;
            }
        }
        if (LeaseTerminationServices.isTerminated(agreement)
                || LeaseTerminationServices.findTermination(delegator, leaseAgreementId) != null) {
            return true;
        }
        long installments = EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                .where("leaseAgreementId", leaseAgreementId,
                        "scheduleRevisionNumber", LeaseAccountingServices.currentScheduleRevision(delegator, leaseAgreementId))
                .queryCount();
        return EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                .where("leaseAgreementId", leaseAgreementId).queryCount() >= installments;
    }

    // ------------------------------------------------------------------
    // Balance
    // ------------------------------------------------------------------

    /** Received / applied / refunded / balance of the advance lease payments and of the deposits. */
    public static Map<String, BigDecimal> balance(Delegator delegator, String leaseAgreementId)
            throws GenericEntityException {
        BigDecimal[] advance = new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal[] deposit = new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (GenericValue r : receipts(delegator, leaseAgreementId, null)) {
            BigDecimal[] sums = TYPE_DEPOSIT.equals(r.getString("advanceTypeEnumId")) ? deposit : advance;
            sums[0] = sums[0].add(nz(r.getBigDecimal("amount")));
            sums[1] = sums[1].add(appliedToInvoices(delegator, r));
            sums[2] = sums[2].add(nz(r.getBigDecimal("refundedAmount")));
        }
        Map<String, BigDecimal> result = new HashMap<>();
        result.put("advanceReceivedAmount", advance[0]);
        result.put("advanceAppliedAmount", advance[1]);
        result.put("advanceRefundedAmount", advance[2]);
        result.put("advanceBalance", advance[0].subtract(advance[1]).subtract(advance[2]));
        result.put("depositReceivedAmount", deposit[0]);
        result.put("depositAppliedAmount", deposit[1]);
        result.put("depositRefundedAmount", deposit[2]);
        result.put("depositBalance", deposit[0].subtract(deposit[1]).subtract(deposit[2]));
        return result;
    }
}
